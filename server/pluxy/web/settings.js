// Settings are staged locally until persistence succeeds. Scans use saved folders.
let CFG = null;
let baseline = '';
let saving = false;
let picking = false;
let scanning = false;
let statusPending = false;
let statusTimer;
let toastTimer;
let desktopServerState = null;
let folderError = '';
const $ = id => document.getElementById(id);

function toast(message, error = false) {
  if (error && $('folderManager').open) {
    folderError = message;
    $('folderSaveState').textContent = message;
  }
  const element = $('toast');
  element.textContent = message;
  element.className = 'toast show' + (error ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => element.className = 'toast', 5000);
}

async function request(url, options = {}, timeout = 15000) {
  const controller = new AbortController();
  const timer = timeout ? setTimeout(() => controller.abort(), timeout) : null;
  try {
    const response = await fetch(url, {...options, signal: controller.signal});
    const data = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(typeof data?.detail === 'string' ? data.detail : 'Requête refusée (' + response.status + ').');
      error.status = response.status;
      throw error;
    }
    if (!data) throw new Error('Réponse du serveur illisible.');
    return data;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('Le serveur ne répond pas. Réessayez dans un instant.');
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function folders() {
  return [...new Set((CFG?.server.media_dirs || []).map(path => path.trim()).filter(Boolean))];
}

function readPatch() {
  return {
    server: {name: $('serverName').value.trim() || 'Pluxy', port: Number($('port').value), media_dirs: folders(), media_dir_types: Object.fromEntries(folders().map(path => [path, CFG.server.media_dir_types?.[path] || 'auto'])), autostart: $('autostart').checked},
    discovery: {enabled: $('discovery').checked},
    transcoding: {prefer_direct_play: $('preferDirect').checked, hardware_acceleration: $('hw').checked, force_transcode: $('force').checked, preset: $('preset').value, max_bitrate_mbps: Number($('bitrate').value), hdr_tone_mapping: $('tonemap').value},
    audio: {force_audio_passthrough: $('passthrough').checked, target_codec: $('acodec').value},
    client_buffer: {target_buffer_bytes_mb: Number($('bufmb').value)},
    subtitles: {prefer_external: $('externalSubs').checked},
    metadata: {enabled: $('metaEnabled').checked, tmdb_api_key: $('tmdbKey').value.trim(), language: $('metaLang').value},
    network: {delivery: $('delivery').value}
  };
}

function isDirty() {
  return !!CFG && JSON.stringify(readPatch()) !== baseline;
}

function updateControls() {
  document.querySelectorAll('button, input, select').forEach(element => {
    if (element.closest('#desktopMenu') || element.closest('.pluxy-update') || element.closest('form[method="dialog"]') || element.id === 'desktopOptions') return;
    if (element.id !== 'retryButton') element.disabled = !CFG || saving || picking;
  });
  $('scanButton').disabled = !CFG || saving || picking || scanning;
  $('saveButton').disabled = !CFG || saving || picking || !isDirty();
  $('saveButton').textContent = saving ? 'Enregistrement…' : 'Enregistrer les réglages';
  $('saveState').textContent = saving ? 'Enregistrement en cours…' : !CFG ? 'Chargement des réglages…' : isDirty() ? 'Modifications à enregistrer' : 'Tous les réglages sont enregistrés';
  $('folderSaveState').textContent = folderError || $('saveState').textContent;
  $('saveFolders').disabled = $('saveButton').disabled;
  $('saveFolders').textContent = saving ? 'Enregistrement…' : 'Enregistrer';
  if (desktopServerState && (desktopServerState.busy || !desktopServerState.running)) {
    document.querySelectorAll('main button, main input, main select, .actions button, #folderManager button, #folderManager input, #folderManager select').forEach(element => { if (!element.closest('form[method="dialog"]')) element.disabled = true; });
    $('saveState').textContent = desktopServerState.busy ? desktopServerState.status : 'Serveur arrêté';
    $('statusText').textContent = desktopServerState.busy ? desktopServerState.status : 'Arrêté';
    $('statusDot').className = 'dot';
    $('folderSaveState').textContent = $('saveState').textContent;
  }
  updateDesktopControls();
}

function updateDesktopControls() {
  if (!window.chrome?.webview) return;
  const state = desktopServerState;
  document.querySelectorAll('[data-desktop]').forEach(button => {
    const action = button.dataset.desktop;
    button.disabled = !state || state.busy || saving || picking;
    if (action === 'serverStart') button.disabled ||= !!state?.running;
    if (action === 'serverStop' || action === 'serverRestart') button.disabled ||= !state?.canStop;
  });
  $('desktopServerStatus').textContent = state?.status || 'Vérification du serveur…';
}

function renderFolders() {
  const list = $('folderList');
  const items = folders();
  const categories = [['movie','Films'],['series','Séries'],['clip','Vidéos'],['photo','Photos'],['audio','Musique'],['auto','Automatique']];
  const categoryFor = path => CFG.server.media_dir_types?.[path] || 'auto';
  $('folderCount').textContent = items.length ? `· ${items.length} dossier${items.length > 1 ? 's' : ''}` : '';
  const summary = $('folderSummary');
  summary.replaceChildren();
  categories.forEach(([value, title]) => {
    const count = items.filter(path => categoryFor(path) === value).length;
    if (!count) return;
    const badge = document.createElement('span');
    badge.textContent = `${title} · ${count}`;
    summary.append(badge);
  });
  if (!items.length) summary.textContent = 'Ajoutez un dossier pour commencer.';
  $('setupGuide').hidden = items.length > 0;
  list.replaceChildren();
  if (!items.length) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = 'Aucun dossier : ajoutez au moins un emplacement contenant vos médias.';
    list.append(empty);
  }
  const searchable = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('fr');
  const query = searchable($('folderSearch').value.trim());
  const filtered = items.filter(path => searchable(path).includes(query));
  const collator = new Intl.Collator('fr', {numeric:true, sensitivity:'base'});
  if (items.length && !filtered.length) {
    const empty = document.createElement('p'); empty.className = 'empty'; empty.textContent = 'Aucun dossier ne correspond à cette recherche.'; list.append(empty);
  }
  categories.forEach(([value, title]) => {
    const matching = filtered.filter(path => categoryFor(path) === value).sort(collator.compare);
    if (!matching.length) return;
    const heading = document.createElement('h3'); heading.textContent = `${title} · ${matching.length}`; list.append(heading);
    matching.forEach(path => {
    const chip = document.createElement('div');
    chip.className = 'folder';
    const label = document.createElement('span');
    const name = document.createElement('strong');
    name.textContent = path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || path;
    const location = document.createElement('small'); location.textContent = path;
    label.append(name, location);
    label.title = path;
    const remove = document.createElement('button');
    const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    icon.setAttribute('class', 'icon');
    icon.setAttribute('viewBox', '0 0 24 24');
    icon.setAttribute('aria-hidden', 'true');
    const cross = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    cross.setAttribute('d', 'M6.5 6.5l11 11M17.5 6.5l-11 11');
    icon.append(cross);
    remove.append(icon);
    remove.setAttribute('aria-label', 'Retirer le dossier ' + path);
    remove.onclick = () => {
      CFG.server.media_dirs = items.filter(item => item !== path);
      renderFolders();
      updateControls();
    };
    const category = document.createElement('select');
    category.setAttribute('aria-label', 'Catégorie du dossier ' + path);
    [['auto', 'Détection automatique'], ['movie', 'Films'], ['series', 'Séries'], ['clip', 'Vidéos personnelles / autres'], ['photo', 'Photos'], ['audio', 'Musique']].forEach(([value, text]) => {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = text;
      category.append(option);
    });
    category.value = CFG.server.media_dir_types?.[path] || 'auto';
    category.dataset.path = path;
    category.onchange = () => {
      CFG.server.media_dir_types ||= {};
      CFG.server.media_dir_types[path] = category.value;
      renderFolders();
      updateControls();
      [...list.querySelectorAll('select')].find(element => element.dataset.path === path)?.focus();
    };
    chip.append(label, category, remove);
    list.append(chip);
    });
  });
}

$('manageFolders').addEventListener('click', () => { folderError = ''; $('folderSearch').value = ''; renderFolders(); updateControls(); $('folderManager').showModal(); });
$('folderSearch').addEventListener('input', renderFolders);
$('manualFolderForm').addEventListener('submit', event => { event.preventDefault(); addManualFolder(); });

function setRange(id, value, label, unit) {
  $(id).value = value;
  $(label).textContent = $(id).value + ' ' + unit;
}

function setSelect(id, value) {
  const select = $(id);
  select.querySelectorAll('option[data-current]').forEach(option => option.remove());
  if (![...select.options].some(option => option.value === value)) {
    const option = new Option(value, value);
    option.dataset.current = 'true';
    select.add(option);
  }
  select.value = value;
}

function loadForm() {
  const {server: s, transcoding: t, audio: a, client_buffer: b, network: n, metadata: m, subtitles: sub, discovery: d} = CFG;
  $('serverName').value = s.name;
  $('port').value = s.port;
  $('autostart').checked = s.autostart;
  $('discovery').checked = d.enabled;
  $('preferDirect').checked = t.prefer_direct_play;
  $('hw').checked = t.hardware_acceleration;
  $('force').checked = t.force_transcode;
  setSelect('preset', t.preset);
  setRange('bitrate', t.max_bitrate_mbps, 'bitrateVal', 'Mbps');
  $('tonemap').value = t.hdr_tone_mapping;
  $('passthrough').checked = a.force_audio_passthrough;
  $('acodec').value = a.target_codec;
  setRange('bufmb', b.target_buffer_bytes_mb, 'bufmbVal', 'Mo');
  $('externalSubs').checked = sub.prefer_external;
  $('metaEnabled').checked = m.enabled;
  $('tmdbKey').value = m.tmdb_api_key;
  setSelect('metaLang', m.language);
  $('delivery').value = n.delivery;
  renderFolders();
  baseline = JSON.stringify(readPatch());
  updateControls();
}

async function load() {
  $('retryButton').disabled = true;
  try {
    const config = await request('/api/settings');
    if (!['server', 'transcoding', 'audio', 'client_buffer', 'network', 'metadata', 'subtitles', 'discovery'].every(key => config[key])) {
      throw new Error('Réglages incomplets reçus du serveur.');
    }
    CFG = config;
    loadForm();
    $('loadError').hidden = true;
    await updateStatus();
  } catch (error) {
    $('loadError').hidden = false;
    $('loadErrorText').textContent = error.message;
    $('statusText').textContent = 'Connexion impossible';
  } finally {
    $('retryButton').disabled = false;
    updateControls();
  }
}

async function updateStatus() {
  if (statusPending || desktopServerState && !desktopServerState.running) return;
  statusPending = true;
  clearTimeout(statusTimer);
  try {
    const info = await request('/api/server/info', {}, 6000);
    scanning = !!info.scanning;
    $('mediaCount').textContent = info.media_count ?? 0;
    $('scanState').textContent = scanning ? 'Analyse…' : info.media_count ? 'Prête' : 'À remplir';
    // The configured port applies after restart; keep the live address accurate.
    const activePort = Number(location.port || 80);
    $('serverAddress').textContent = (info.primary_ip || 'localhost') + ':' + activePort;
    $('serverAddress').title = $('serverAddress').textContent;
    $('ffmpegState').textContent = info.transcoding_available ? 'Disponible' : 'Non détecté';
    $('statusText').textContent = scanning ? 'Analyse en cours' : 'Serveur actif';
    $('statusDot').className = 'dot ok';
  } catch (error) {
    $('statusText').textContent = 'Serveur indisponible';
    $('statusDot').className = 'dot';
  } finally {
    statusPending = false;
    updateControls();
    if (!document.hidden && (!desktopServerState || desktopServerState.running)) statusTimer = setTimeout(updateStatus, 5000);
  }
}

async function pickFolder() {
  if (!CFG || saving || picking) return;
  picking = true;
  updateControls();
  try {
    // Windows owns the dialog; choosing a folder can take longer than an HTTP timeout.
    const result = await request('/api/settings/pick-folder', {method: 'POST'}, 0);
    if (!result.path) return;
    CFG.server.media_dirs = [...new Set([...folders(), result.path])];
    $('folderSearch').value = '';
    renderFolders();
    updateControls();
    if (!$('folderManager').open) $('folderManager').showModal();
  } catch (error) {
    if (error.status === 503 && !$('folderManager').open) $('folderManager').showModal();
    toast(error.message + (error.status === 503 ? ' Saisissez le chemin manuellement.' : ''), true);
  } finally {
    picking = false;
    updateControls();
  }
}

function addManualFolder() {
  if (!CFG || saving || picking) return;
  const input = $('manualFolder');
  const path = input.value.trim();
  if (!path) return;
  CFG.server.media_dirs = [...new Set([...folders(), path])];
  input.value = '';
  renderFolders();
  updateControls();
}

async function save() {
  if (!CFG || saving || picking) return false;
  folderError = '';
  if (!isDirty()) return true;
  if (!$('port').reportValidity()) return false;
  const patch = readPatch();
  const portChanged = patch.server.port !== Number(location.port || 80);
  saving = true;
  updateControls();
  try {
    CFG = await request('/api/settings', {method: 'PATCH', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(patch)});
    loadForm();
    $('restartNotice').hidden = !portChanged;
    toast(portChanged ? 'Réglages enregistrés. Redémarrez Pluxy pour appliquer le nouveau port.' : 'Réglages enregistrés');
    return true;
  } catch (error) {
    toast(error.message, true);
    return false;
  } finally {
    saving = false;
    updateControls();
  }
}

async function rescan() {
  if (!CFG || saving || picking || scanning) return;
  if (isDirty() && !await save()) return;
  if (!folders().length) {
    toast('Ajoutez un dossier avant de lancer l’analyse.', true);
    if (!$('folderManager').open) $('folderManager').showModal();
    $('manualFolder').focus();
    return;
  }
  scanning = true;
  updateControls();
  try {
    const result = await request('/api/library/scan', {method: 'POST'});
    toast(result.status === 'scanning' ? 'Analyse lancée' : 'Analyse déjà en cours');
  } catch (error) {
    scanning = false;
    toast(error.message, true);
  } finally {
    await updateStatus();
  }
}

async function refreshMetadata() {
  if (!CFG || saving || picking) return;
  if (isDirty() && !await save()) return;
  try {
    await request('/api/library/refresh-metadata', {method: 'POST'});
    toast('Actualisation TMDB lancée');
  } catch (error) {
    toast(error.message, true);
  }
}

async function copyAddress() {
  try {
    const info = await request('/api/server/info');
    const address = new URL(info.base_url);
    address.port = location.port;
    await navigator.clipboard.writeText(address.href);
    toast('Adresse copiée : ' + address.href);
  } catch (error) {
    toast('Copie impossible : utilisez l’adresse affichée.', true);
  }
}

$('bitrate').oninput = event => $('bitrateVal').textContent = event.target.value + ' Mbps';
$('bufmb').oninput = event => $('bufmbVal').textContent = event.target.value + ' Mo';
$('manualFolder').onkeydown = event => { if (event.key === 'Enter') addManualFolder(); };
document.querySelectorAll('input, select').forEach(element => {
  element.addEventListener('input', updateControls);
  element.addEventListener('change', updateControls);
});
document.addEventListener('visibilitychange', () => {
  clearTimeout(statusTimer);
  if (!document.hidden && CFG) updateStatus();
});
window.addEventListener('beforeunload', event => {
  if (isDirty() || saving) { event.preventDefault(); event.returnValue = ''; }
});
updateControls();
load();

async function checkDiagnostics() {
  const result = $('diagnosticsResult');
  result.textContent = 'Vérification en cours…';
  $('diagnosticsButton').disabled = true;
  try {
    const response = await fetch('/api/settings/diagnostics');
    if (!response.ok) throw new Error('Diagnostic indisponible.');
    const info = await response.json();
    const labels = {ok: 'Connexion TMDB fonctionnelle', missing_key: 'Clé TMDB absente', invalid_key: 'Clé TMDB refusée', disabled: 'TMDB désactivé', rate_limit: 'TMDB demande de patienter', network_error: 'TMDB inaccessible sur ce réseau', service_error: 'TMDB indisponible'};
    result.textContent = `${labels[info.tmdb] || 'TMDB indisponible'}. ${info.posters} affiches pour ${info.videos_to_match} films / épisodes. Miniatures vidéo : ${info.ffmpeg && info.ffprobe ? 'outils disponibles' : 'FFmpeg / ffprobe manquants : relancez Pluxy.bat'}. Les vidéos personnelles utilisent une capture locale, sans TMDB.`;
  } catch (error) { result.textContent = error.message; }
  finally { $('diagnosticsButton').disabled = false; }
}

if (window.chrome?.webview) {
  $('desktopOptions').hidden = false;
  $('desktopOptions').addEventListener('click', () => $('desktopMenu').showModal());
  window.chrome.webview.addEventListener('message', event => {
    const message = event.data;
    if (message.eventName === 'serverState') {
      const previous = desktopServerState;
      desktopServerState = message.data;
      if (!desktopServerState.running) clearTimeout(statusTimer);
      updateControls();
      if (desktopServerState.running && !previous?.running && CFG) updateStatus();
    } else if (message.eventName === 'serverError') toast(message.data, true);
  });
  document.querySelectorAll('[data-desktop]').forEach(button => button.addEventListener('click', async () => {
    if ((button.dataset.desktop.startsWith('server') || button.dataset.desktop === 'updates') && desktopServerState?.running && isDirty() && !await save()) return;
    $('desktopMenu').close();
    window.chrome.webview.postMessage({method: button.dataset.desktop});
  }));
  updateDesktopControls();
  window.chrome.webview.postMessage({method: 'serverState'});
}
