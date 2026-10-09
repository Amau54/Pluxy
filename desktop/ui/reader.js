/* Bibliothèque du lecteur Windows. Les accès au réseau passent par l'hôte natif. */
'use strict';
const $ = id => document.getElementById(id);
const pending = new Map();
let sequence = 0, prefs = {}, connection = null, kind = 'movie', items = [], next = null, revision = '', generation = 0;
let reconnecting = false, toastTimer, searchTimer, slideTimer, photos = [], photoIndex = 0, groups = [];
const labels = {movie: 'Films', series: 'Séries', clip: 'Vidéos', photo: 'Photos', audio: 'Musique'};
const iconNames = {movie: 'film', series: 'series', clip: 'play', photo: 'photos', audio: 'music'};
function call(method, args = {}) {
  return new Promise((resolve, reject) => {
    const id = String(++sequence);
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error('La connexion prend trop de temps. Réessayez.')); }, method === 'mode' ? 1250000 : 30000);
    pending.set(id, {resolve, reject, timeout});
    window.chrome.webview.postMessage({id, method, args});
  });
}
window.chrome.webview.addEventListener('message', ({data}) => {
  if (data.eventName === 'status') { $('loading-text').textContent = data.data; return; }
  if (data.eventName === 'progressUpdated') { if (prefs.mode === 'reader') load(); return; }
  const task = pending.get(data.id); if (!task) return;
  pending.delete(data.id); clearTimeout(task.timeout);
  data.error ? task.reject(new Error(data.error)) : task.resolve(data.result);
});
function node(tag, classes, text) { const e = document.createElement(tag); if (classes) e.className = classes; if (text != null) e.textContent = text; return e; }
function icon(name) { const e = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); const use = document.createElementNS(e.namespaceURI, 'use'); use.setAttribute('href', '#icon-' + name); e.append(use); return e; }
function button(text, action, classes = '') { const e = node('button', classes, text); e.type = 'button'; e.addEventListener('click', action); return e; }
function hidden(id, value) { $(id).classList.toggle('hidden', value); }
function showDialog(id) { if (!$(id).open) $(id).showModal(); }
function toast(text) { $('toast').textContent = text; hidden('toast', false); clearTimeout(toastTimer); toastTimer = setTimeout(() => hidden('toast', true), 3500); }
function loading(text) { $('loading-text').textContent = text; hidden('loading', false); }
function fail(error) { toast(error.message || 'Impossible de terminer cette action.'); }
function proxy(path) { return 'https://media.pluxy.invalid/remote?server=' + encodeURIComponent(connection.url) + '&path=' + encodeURIComponent(path); }
function thumbnail(item) { return proxy('/api/library/items/' + encodeURIComponent(item.id) + '/thumbnail'); }
function image(item, classes = '', full = false) {
  const e = node('img', classes); e.alt = ''; e.loading = 'lazy'; e.decoding = 'async';
  let remote = false;
  try { const url = new URL(item.poster_url); remote = url.origin === 'https://image.tmdb.org'; } catch (_) { /* Affiche locale. */ }
  e.src = full ? proxy('/stream/direct/' + encodeURIComponent(item.id)) : remote ? item.poster_url : thumbnail(item);
  e.addEventListener('error', () => { if (remote) { remote = false; e.src = thumbnail(item); } else e.remove(); });
  return e;
}
function title(item) { return item.metadata_title || item.title || 'Sans titre'; }
function minutes(item) { return item.duration ? Math.round(item.duration / 60) + ' min' : ''; }
function folded(value) { return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(); }
function applyPreferences() {
  document.documentElement.dataset.theme = ['coral','ocean','midnight'].includes(prefs.theme) ? prefs.theme : 'coral';
  $('theme').value = prefs.theme || 'coral'; $('audio').value = prefs.audio || 'auto';
}
async function chooseMode(mode) {
  loading(mode === 'server' ? 'Préparation du serveur…' : 'Ouverture du lecteur…');
  try { await call('mode', {mode}); prefs.mode = mode; if (mode === 'reader') await openReader(); }
  catch (error) { fail(error); }
  finally { hidden('loading', true); }
}
async function openReader() {
  hidden('welcome', true); hidden('reader', false);
  if (connection) { await load(); return; }
  if (prefs.server) { try { await connect(prefs.server); return; } catch (_) { /* Recherche du serveur disponible. */ } }
  await discover(true);
}
async function connect(url) {
  $('connection-status').textContent = 'Connexion…';
  const selected = await call('connect', {url});
  connection = selected; prefs.server = selected.url; prefs.serverName = selected.name;
  $('server-name').textContent = selected.name; $('server-choice').classList.remove('offline');
  $('address').value = selected.url; $('connection').close(); $('connection-status').textContent = '';
  await load();
}
async function discover(automatic = false) {
  if (reconnecting) return; reconnecting = true;
  $('connection-status').textContent = 'Recherche sur le réseau…';
  try {
    const found = await call('discover');
    $('server-list').replaceChildren(...found.map(server => {
      const e = button('', () => connect(server.url).catch(error => { $('connection-status').textContent = error.message; }), 'discovered-server');
      e.append(icon('server'), node('strong', '', server.name), node('span', 'muted', server.url)); return e;
    }));
    if (automatic && found.length === 1) { await connect(found[0].url); return; }
    $('connection-status').textContent = found.length ? 'Sélectionnez le serveur à utiliser.' : 'Aucun serveur détecté. Vérifiez le réseau ou saisissez son adresse.';
    showDialog('connection');
  } catch (error) { $('connection-status').textContent = error.message; showDialog('connection'); }
  finally { reconnecting = false; }
}
function filters() { return {kind, q: $('search').value.trim(), genre: $('genre').value, sort: $('sort').value, descending: $('sort').value !== 'title'}; }
async function load(append = false, retry = true) {
  if (!connection) { $('content').replaceChildren(node('p','empty','Choisissez un serveur pour ouvrir votre bibliothèque.')); return; }
  const version = ++generation; const selectedKind = kind; const query = filters();
  if (!append) { items = []; next = null; revision = ''; $('content').replaceChildren(node('p','empty','Chargement de la bibliothèque…')); }
  hidden('more', true);
  try {
    let page = await call('page', {...query, offset: append ? next : 0, revision: append ? revision : ''});
    if (version !== generation) return;
    items = append ? [...items, ...page.items] : page.items; revision = page.revision; next = page.next_offset;
    // Une série et un arbre de dossiers doivent être complets avant d'être regroupés.
    if (selectedKind === 'series' || selectedKind === 'photo' || selectedKind === 'audio') {
      while (next != null && next <= 10000) {
        page = await call('page', {...query, offset: next, revision});
        if (version !== generation) return;
        items.push(...page.items); next = page.next_offset;
      }
    }
    if (version !== generation) return;
    $('count').textContent = page.total + (selectedKind === 'series' ? ' épisodes' : selectedKind === 'photo' ? ' photos' : ' éléments');
    render(); hidden('more', next == null);
    if (!append) {
      const facets = await call('facets', {kind: selectedKind});
      if (version !== generation) return;
      const value = $('genre').value; $('genre').replaceChildren(new Option('Tous les genres', ''), ...(facets.genres || []).map(g => new Option(g, g))); $('genre').value = value;
    }
  } catch (error) {
    if (version !== generation) return;
    if (retry && error.message.includes('409')) { await load(false, false); return; }
    $('content').replaceChildren(node('p','empty',error.message));
    $('server-choice').classList.add('offline');
    await discover(true);
  }
}
function card(item, action, subtitle) {
  const e = button('', action, 'media-card');
  const poster = node('div','poster'); poster.append(icon(iconNames[item.kind] || 'film'), image(item));
  const quality = [item.width >= 3840 || item.height >= 2160 ? '4K' : '', item.is_hdr ? 'HDR' : ''].filter(Boolean).join(' · ');
  if (quality) poster.append(node('span','quality',quality));
  if (item.watched) poster.append(node('span','watched','Vu'));
  e.append(poster, node('strong','media-title',title(item)), node('span','media-subtitle',subtitle == null ? [item.year, minutes(item)].filter(Boolean).join(' · ') : subtitle));
  e.title = title(item); return e;
}
function render() {
  hidden('photo-commands', kind !== 'photo'); $('genre').hidden = kind === 'photo' || kind === 'audio';
  if (!items.length) { $('content').replaceChildren(node('p','empty',$('search').value ? 'Aucun résultat pour cette recherche.' : 'Cette rubrique est vide. Ajoutez des dossiers dans le serveur.')); return; }
  if (kind === 'photo') { renderPhotos(); return; }
  if (kind === 'series') { renderSeries(); return; }
  if (kind === 'audio') { renderMusic(); return; }
  const grid = node('div', 'media-grid ' + (kind === 'clip' ? 'clips' : ''));
  grid.append(...items.map(item => card(item, () => kind === 'clip' ? play(item) : showMovie(item)))); $('content').replaceChildren(grid);
}
async function play(item, resume = true) {
  try {
    const progress = resume ? await call('progress', {id: item.id}) : null;
    await call('play', {id: item.id, start_ms: progress && !progress.watched ? progress.position_ms || 0 : 0});
  } catch (error) { fail(error); }
}
async function showMovie(item) {
  const body = $('detail-content'); const poster = node('div','detail-poster'); poster.append(image(item));
  const text = node('div','detail-text'); text.append(node('p','eyebrow',item.kind === 'series' ? 'Série' : 'Film'), node('h2','',title(item)), node('p','muted',[item.year, minutes(item), item.is_hdr ? 'HDR' : '', item.width >= 3840 ? '4K' : ''].filter(Boolean).join(' · ')));
  const actions = node('div','detail-actions'); actions.append(button(item.in_progress ? 'Reprendre' : 'Lire', () => play(item), 'primary'));
  if (item.in_progress) actions.append(button('Depuis le début', () => play(item, false)));
  text.append(actions); const overview = node('p','overview',''); text.append(overview);
  const row = node('div','detail-row'); row.append(poster,text); body.replaceChildren(row); showDialog('details');
  try { const metadata = await call('metadata',{id:item.id}); if (body.contains(overview)) overview.textContent = metadata.overview || ''; } catch (_) { /* La lecture reste disponible sans métadonnées. */ }
}
function seriesKey(item) {
  // Le serveur récent fournit une clé canonique. Ce repli couvre les index plus anciens.
  return folded(item.series_key || item.series_name || title(item)).replace(/\b(saison|season|s)\s*\d+\b/g,'').replace(/[^a-z0-9]+/g,' ').trim();
}
function renderSeries() {
  const series = new Map();
  for (const item of items) { const key = seriesKey(item); if (!series.has(key)) series.set(key, []); series.get(key).push(item); }
  groups = [...series.values()];
  const grid = node('div','media-grid');
  grid.append(...groups.map(episodes => {
    const first = episodes[0], seasons = new Set(episodes.map(e => e.season || 0));
    return card({...first, metadata_title: first.series_name || title(first)}, () => showSeries(episodes), seasons.size + (seasons.size > 1 ? ' saisons' : ' saison') + ' · ' + episodes.length + ' épisodes');
  }));
  $('count').textContent = groups.length + ' séries · ' + items.length + ' épisodes'; $('content').replaceChildren(grid);
}
function showSeries(episodes) {
  episodes = [...episodes].sort((a,b) => (a.season || 0) - (b.season || 0) || (a.episode || 0) - (b.episode || 0) || title(a).localeCompare(title(b),'fr',{numeric:true}));
  const first = episodes[0], body = $('detail-content'), row = node('div','detail-row'), poster = node('div','detail-poster'), text = node('div','detail-text');
  poster.append(image(first)); text.append(node('p','eyebrow','Série'),node('h2','',first.series_name || title(first)),node('p','muted',episodes.length + ' épisodes'));
  const resume = [...episodes].filter(e => e.in_progress).sort((a,b) => b.last_played_at - a.last_played_at)[0] || episodes.find(e => !e.watched) || first;
  text.append(button('Lire · S' + (resume.season || 0) + ' E' + (resume.episode || 0), () => play(resume),'primary')); row.append(poster,text);
  const seasons = node('div','season-tabs'), list = node('div','episode-list');
  function showSeason(season) {
    seasons.querySelectorAll('button').forEach(b => b.classList.toggle('active',Number(b.dataset.season) === season));
    list.replaceChildren(...episodes.filter(e => (e.season || 0) === season).map(item => {
      const e = button('', () => play(item),'episode-row'); e.classList.toggle('is-watched',Boolean(item.watched)); e.append(image(item),node('span','episode-number',String(item.episode || '—')),node('strong','',title(item)),node('span','muted',item.watched ? 'Vu' : item.in_progress ? 'Reprendre' : minutes(item)),icon('play')); return e;
    }));
  }
  for (const season of [...new Set(episodes.map(e => e.season || 0))]) { const e = button(season ? 'Saison ' + season : 'Épisodes spéciaux',() => showSeason(season)); e.dataset.season = season; seasons.append(e); }
  body.replaceChildren(row,seasons,list); showSeason(resume.season || 0); showDialog('details');
}
function photoState() { try { return JSON.parse(localStorage.getItem('folders:' + connection.url) || '{}'); } catch (_) { return {}; } }
function renderPhotos() {
  const roots = new Map(), state = photoState();
  for (const item of items) {
    const source = item.source_id || item.source_name || 'photos';
    if (!roots.has(source)) roots.set(source,{name:item.source_name || 'Photos',key:source,children:new Map(),images:[]});
    let folder = roots.get(source);
    const path = (item.relative_path || '').replace(/\\/g,'/').split('/').filter(Boolean); path.pop();
    for (const part of path) { if (!folder.children.has(part)) folder.children.set(part,{name:part,key:folder.key + '/' + part,children:new Map(),images:[]}); folder = folder.children.get(part); }
    folder.images.push(item);
  }
  groups = [];
  function branch(folder, root = false) {
    if (folder.images.length) groups.push(folder.images);
    const e = node('details','folder-tree'); e.open = Object.prototype.hasOwnProperty.call(state,folder.key) ? Boolean(state[folder.key]) : root;
    const summary = node('summary','folder-row'); summary.append(icon('chevron'),icon('folder'),node('strong','',folder.name),node('span','muted',folder.images.length ? folder.images.length + ' photos' : ''));
    e.append(summary); e.dataset.folder = folder.key;
    e.addEventListener('toggle',() => { const saved = photoState(); saved[folder.key] = e.open; try { localStorage.setItem('folders:' + connection.url,JSON.stringify(saved)); } catch (_) { /* Stockage plein. */ } });
    if (folder.images.length) { const grid = node('div','media-grid photos'); grid.append(...folder.images.map(item => card(item,() => openPhoto(item),''))); e.append(grid); }
    for (const child of [...folder.children.values()].sort((a,b) => a.name.localeCompare(b.name,'fr',{numeric:true}))) e.append(branch(child));
    return e;
  }
  $('content').replaceChildren(...[...roots.values()].map(root => branch(root,true)));
}
function renderMusic() {
  const albums = new Map();
  for (const item of items) { const key = folded(item.artist) + '/' + folded(item.album || item.source_name); if (!albums.has(key)) albums.set(key,[]); albums.get(key).push(item); }
  const grid = node('div','media-grid music');
  grid.append(...[...albums.values()].map(tracks => {
    const first = tracks[0]; return card({...first,metadata_title:first.album || 'Sans album'},() => {
      const list = node('div','episode-list');
      tracks.sort((a,b) => (a.disc_number || 1) - (b.disc_number || 1) || (a.track_number || 0) - (b.track_number || 0) || title(a).localeCompare(title(b),'fr',{numeric:true}));
      list.append(...tracks.map(item => { const e = button('',() => play(item,false),'episode-row'); e.append(node('span','episode-number',String(item.track_number || '♪')),node('strong','',title(item)),node('span','muted',minutes(item)),icon('play')); return e; }));
      $('detail-content').replaceChildren(node('h2','',first.album || 'Sans album'),node('p','muted',first.artist || ''),list); showDialog('details');
    },first.artist || tracks.length + ' pistes');
  })); $('content').replaceChildren(grid);
}
function openPhoto(item) { photos = groups.find(group => group.some(p => p.id === item.id)) || [item]; photoIndex = photos.findIndex(p => p.id === item.id); showPhoto(); showDialog('gallery'); }
function showPhoto() {
  const item = photos[photoIndex]; if (!item) return;
  $('gallery-image').src = proxy('/stream/direct/' + encodeURIComponent(item.id)); $('gallery-image').alt = title(item);
  $('gallery-name').textContent = title(item) + ' · ' + (photoIndex + 1) + ' / ' + photos.length;
  for (const offset of [-2,-1,1,2]) { const near = photos[(photoIndex + offset + photos.length) % photos.length]; const preloaded = new Image(); preloaded.src = proxy('/stream/direct/' + encodeURIComponent(near.id)); }
}
function stepPhoto(delta) { if (photos.length) { photoIndex = (photoIndex + delta + photos.length) % photos.length; showPhoto(); } }
function stepFolder(delta) {
  if (!groups.length) return;
  const index = groups.indexOf(photos); photos = groups[(index + delta + groups.length) % groups.length]; photoIndex = 0; showPhoto();
  toast(photos[0].folder || photos[0].source_name || 'Photos');
}
function stopSlideshow() { clearInterval(slideTimer); slideTimer = null; $('slideshow').textContent = 'Diaporama'; }
document.querySelectorAll('[data-mode]').forEach(e => e.addEventListener('click',() => chooseMode(e.dataset.mode)));
document.querySelectorAll('[data-kind]').forEach(e => e.addEventListener('click',() => {
  kind = e.dataset.kind; $('heading').textContent = labels[kind]; $('genre').value = ''; $('search').value = '';
  document.querySelectorAll('[data-kind]').forEach(b => b.classList.toggle('active',b === e)); load();
}));
document.querySelector('[data-kind="movie"]').classList.add('active');
$('connect-manual').addEventListener('submit',async event => { event.preventDefault(); try { await connect($('address').value); } catch (error) { $('connection-status').textContent = error.message; } });
$('server-choice').addEventListener('click',() => { showDialog('connection'); discover(); }); $('discover').addEventListener('click',() => discover());
$('more').addEventListener('click',() => load(true)); $('refresh').addEventListener('click',() => load());
['genre','sort'].forEach(id => $(id).addEventListener('change',() => load()));
$('search').addEventListener('input',() => { clearTimeout(searchTimer); searchTimer = setTimeout(() => load(),250); });
$('preferences').addEventListener('click',() => showDialog('settings'));
['theme','audio'].forEach(id => $(id).addEventListener('change',async () => { try { await call('preferences',{theme:$('theme').value,audio:$('audio').value}); prefs.theme = $('theme').value; prefs.audio = $('audio').value; applyPreferences(); } catch (error) { fail(error); } }));
$('change-mode').addEventListener('click',async () => { $('settings').close(); try { await call('chooseMode'); } catch (error) { fail(error); } });
$('expand-all').addEventListener('click',() => document.querySelectorAll('.folder-tree').forEach(e => e.open = true));
$('collapse-all').addEventListener('click',() => document.querySelectorAll('.folder-tree').forEach(e => e.open = false));
$('gallery-close').addEventListener('click',() => $('gallery').close()); $('gallery').addEventListener('close',stopSlideshow);
$('gallery-image').addEventListener('error',() => toast('Cette image ne peut pas être affichée. Essayez la suivante.'));
$('photo-next').addEventListener('click',() => stepPhoto(1)); $('photo-previous').addEventListener('click',() => stepPhoto(-1));
$('slideshow').addEventListener('click',() => { if (slideTimer) stopSlideshow(); else { slideTimer = setInterval(() => stepPhoto(1),5000); $('slideshow').textContent = 'Pause'; } });
$('gallery').addEventListener('keydown',event => { if (['ArrowRight','ArrowLeft','ArrowUp','ArrowDown'].includes(event.key)) event.preventDefault(); if (event.key === 'ArrowRight') stepPhoto(1); if (event.key === 'ArrowLeft') stepPhoto(-1); if (event.key === 'ArrowUp') stepFolder(-1); if (event.key === 'ArrowDown') stepFolder(1); });
setInterval(async () => {
  if (!connection || reconnecting || prefs.mode !== 'reader') return;
  try { await call('ping'); $('server-choice').classList.remove('offline'); }
  catch (_) { $('server-choice').classList.add('offline'); await discover(true); }
},20000);
(async () => {
  try { prefs = await call('state'); applyPreferences(); if (prefs.mode === 'reader') await openReader(); else if (prefs.mode === 'server') loading('Ouverture du serveur…'); else hidden('welcome',false); }
  catch (error) { hidden('welcome',false); fail(error); }
})();
