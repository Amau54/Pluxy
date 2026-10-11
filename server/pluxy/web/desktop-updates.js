(() => {
  'use strict';
  if (!window.chrome?.webview) return;
  const dialog = document.createElement('dialog');
  dialog.className = 'pluxy-update';
  dialog.setAttribute('aria-labelledby', 'pluxy-update-title');
  dialog.innerHTML = `<header><h2 id="pluxy-update-title">Mises à jour</h2><button type="button" class="pluxy-update-close" aria-label="Fermer"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6"/></svg></button></header>
    <p class="pluxy-update-version"></p><p class="pluxy-update-status" role="status" aria-live="polite"></p>
    <progress max="100" value="0" aria-label="Téléchargement" hidden></progress>
    <footer><button type="button" class="pluxy-update-later">Plus tard</button><button type="button" class="pluxy-update-action">Vérifier</button></footer>`;
  document.body.append(dialog);
  const action = dialog.querySelector('.pluxy-update-action');
  const close = dialog.querySelector('.pluxy-update-close');
  const later = dialog.querySelector('.pluxy-update-later');
  const progress = dialog.querySelector('progress');
  let state = {};
  const send = action => window.chrome.webview.postMessage({method:'updateAction', args:{action}});
  action.addEventListener('click', () => send(state.available ? 'install' : 'check'));
  [close, later].forEach(button => button.addEventListener('click', () => dialog.close()));
  dialog.addEventListener('close', () => send('dismiss'));
  dialog.addEventListener('cancel', event => { if (state.phase === 'installing') event.preventDefault(); });
  window.chrome.webview.addEventListener('message', ({data}) => {
    if (data.eventName !== 'updateState') return;
    state = data.data;
    dialog.querySelector('.pluxy-update-version').textContent = `Version installée · ${state.current}`;
    dialog.querySelector('.pluxy-update-status').textContent = state.status;
    progress.hidden = state.phase !== 'downloading';
    progress.value = state.percent;
    action.textContent = state.phase === 'installing' ? 'Installation…' : state.available ? 'Télécharger et installer' : 'Vérifier à nouveau';
    action.disabled = state.busy;
    close.disabled = later.disabled = state.phase === 'installing';
    later.textContent = state.phase === 'downloading' ? 'Annuler' : 'Plus tard';
    if (state.visible && !dialog.open) {
      document.querySelectorAll('dialog[open]').forEach(other => other.close());
      dialog.showModal();
    } else if (!state.visible && dialog.open) dialog.close();
  });
})();
