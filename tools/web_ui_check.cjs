// Run against an isolated Pluxy instance. Only the read-only HTML/assets are live;
// settings and actions are intercepted, so this never changes user configuration.
// npm install --no-save playwright; npx playwright install chromium
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLUXY_PLAYWRIGHT_PACKAGE || 'playwright');
const baseURL = process.env.PLUXY_TEST_URL || 'http://127.0.0.1:18420';
const output = process.env.PLUXY_SCREENSHOT_DIR || 'artifacts';

function merge(base, patch) {
  for (const [key, value] of Object.entries(patch)) {
    if (value && typeof value === 'object' && !Array.isArray(value)) merge(base[key], value);
    else base[key] = value;
  }
  return base;
}

(async () => {
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({headless:true, ...(process.env.PLUXY_BROWSER_CHANNEL ? {channel:process.env.PLUXY_BROWSER_CHANNEL} : {})});
  try {
    const context = await browser.newContext({viewport:{width:1440,height:1100}});
    const live = await context.request.get(baseURL + '/api/settings');
    assert.equal(live.status(), 200, 'The local server must serve settings');
    const stylesheet = await context.request.get(baseURL + '/settings.css');
    assert.equal(stylesheet.status(), 200, 'The theme stylesheet must be served');
    assert.ok(stylesheet.headers()['content-type'].includes('text/css'));
    let config = await live.json();
    config.server.media_dirs = [];
    config.server.name = 'Pluxy';
    config.metadata.tmdb_api_key = '';
    config.metadata.language = 'es-ES';
    config.transcoding.preset = 'custom-preset';
    config.transcoding.max_bitrate_mbps = 299;
    config.client_buffer.target_buffer_bytes_mb = 600;
    const actions = [];
    let failSave = false;
    let failLoad = false;
    await context.route('**/api/**', async route => {
      const url = new URL(route.request().url());
      const method = route.request().method();
      const respond = (body, status = 200) => route.fulfill({status, contentType:'application/json', body:JSON.stringify(body)});
      if (url.pathname === '/api/settings' && method === 'GET') {
        return failLoad ? respond({detail:'Chargement impossible'}, 503) : respond(config);
      }
      if (url.pathname === '/api/server/info') return respond({primary_ip:'192.168.1.8',port:8420,base_url:'http://192.168.1.8:8420',media_count:0,scanning:false,transcoding_available:false});
      actions.push(method + ' ' + url.pathname);
      if (url.pathname === '/api/settings/pick-folder') {
        await new Promise(resolve => setTimeout(resolve, 200));
        return respond({path:'D:/Films'});
      }
      if (method === 'PATCH') {
        await new Promise(resolve => setTimeout(resolve, 200));
        if (failSave) return respond({detail:'Disque plein'}, 503);
        config = merge(config, route.request().postDataJSON());
        return respond(config);
      }
      if (url.pathname === '/api/library/scan') return respond({status:'scanning'});
      return respond({status:'ok'});
    });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(baseURL);
    await page.waitForFunction(() => document.querySelector('#saveState').textContent.includes('Tous les réglages'));
    assert.equal(await page.inputValue('#bitrate'), '299', 'Loading must preserve supported bitrate values');
    assert.equal(await page.inputValue('#bufmb'), '600', 'Loading must preserve buffer values without rounding');
    assert.equal(await page.inputValue('#metaLang'), 'es-ES');
    assert.equal(await page.inputValue('#preset'), 'custom-preset');
    assert.equal(await page.locator('#saveButton').isDisabled(), true);
    assert.equal(await page.locator('#setupGuide').isVisible(), true);
    assert.equal(await page.locator('#preferDirect').isChecked(), config.transcoding.prefer_direct_play);
    assert.equal(await page.locator('#hw').isChecked(), config.transcoding.hardware_acceleration);
    assert.equal(await page.locator('#tmdbKey').getAttribute('type'), 'password');
    const unnamed = await page.evaluate(() => [...document.querySelectorAll('input,select')].filter(element => ![...(element.labels || [])].some(label => label.textContent.trim()) && !element.getAttribute('aria-label')).map(element => element.id));
    assert.deepEqual(unnamed, [], 'Every field must have an accessible label');
    for (const width of [320,390,780,1440]) {
      await page.setViewportSize({width,height:width < 800 ? 844 : 1100});
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Horizontal overflow at ${width}px`);
    }
    await page.getByRole('button', {name:'Choisir un dossier',exact:true}).click();
    assert.equal(await page.locator('#saveButton').isDisabled(), true, 'Folder selection cannot race with persistence');
    await page.waitForFunction(() => document.querySelector('#folderList').textContent.includes('D:/Films'));
    assert.equal(await page.locator('#setupGuide').isVisible(), false, 'Onboarding recedes once a folder is present');
    actions.length = 0;
    await page.getByLabel('Chemin du dossier de médias').fill('D:/Films');
    await page.getByLabel('Chemin du dossier de médias').press('Enter');
    await page.locator('#scanButton').click();
    await page.waitForFunction(() => document.querySelector('#toast').textContent === 'Analyse lancée');
    assert.deepEqual(actions, ['PATCH /api/settings', 'POST /api/library/scan'], 'Scan must save folders first');
    assert.deepEqual(config.server.media_dirs, ['D:/Films']);
    assert.equal(config.metadata.language, 'es-ES', 'Saving unrelated settings preserves custom language');
    assert.equal(config.transcoding.preset, 'custom-preset', 'Saving unrelated settings preserves custom preset');
    await page.getByLabel('Nom du serveur').fill('Salon');
    await page.locator('#saveButton').click();
    assert.equal(await page.getByLabel('Nom du serveur').isDisabled(), true, 'Edits are protected during a pending save');
    await page.waitForFunction(() => document.querySelector('#serverName').disabled === false);
    assert.equal(config.server.name, 'Salon');
    await page.locator('summary').filter({hasText:'Métadonnées et options avancées'}).click();
    const beforeInvalid = actions.length;
    await page.getByLabel('Port local', {exact:true}).fill('0');
    await page.locator('#saveButton').click();
    assert.equal(actions.length, beforeInvalid, 'Invalid ports must not reach the API');
    await page.getByLabel('Port local', {exact:true}).fill('18421');
    await page.locator('#saveButton').click();
    await page.waitForFunction(() => !document.querySelector('#restartNotice').hidden);
    assert.ok((await page.locator('#serverAddress').innerText()).endsWith(new URL(baseURL).port), 'Address stays on the running port until restart');
    failSave = true;
    await page.getByLabel('Nom du serveur').fill('Modifications conservées');
    const scansBeforeFailure = actions.filter(action => action.includes('/scan')).length;
    await page.locator('#scanButton').click();
    await page.waitForFunction(() => document.querySelector('#toast').textContent === 'Disque plein');
    assert.equal(await page.getByLabel('Nom du serveur').inputValue(), 'Modifications conservées');
    assert.equal(actions.filter(action => action.includes('/scan')).length, scansBeforeFailure, 'Scan must stop if persistence fails');
    assert.equal(await page.locator('#saveButton').isDisabled(), false);
    failSave = false;
    await page.locator('#saveButton').click();
    await page.waitForFunction(() => document.querySelector('#serverName').disabled === false);
    failLoad = true;
    const recovery = await context.newPage();
    recovery.on('pageerror', error => errors.push(error.message));
    await recovery.goto(baseURL);
    await recovery.locator('#loadError').waitFor({state:'visible'});
    assert.equal(await recovery.locator('#scanButton').isDisabled(), true, 'Actions stay blocked until settings load');
    failLoad = false;
    await recovery.locator('#retryButton').click();
    await recovery.locator('#loadError').waitFor({state:'hidden'});
    assert.equal(await recovery.locator('#serverName').isDisabled(), false);
    assert.deepEqual(errors, [], 'No JavaScript errors');
    // Capture ordinary settings separately from the edge-case configuration above.
    config.server.name = 'Pluxy';
    config.server.port = Number(new URL(baseURL).port || 80);
    config.server.media_dirs = [];
    config.metadata.language = 'fr-FR';
    config.transcoding.preset = 'p5';
    config.transcoding.prefer_direct_play = true;
    config.transcoding.hardware_acceleration = true;
    config.transcoding.max_bitrate_mbps = 50;
    config.client_buffer.target_buffer_bytes_mb = 256;
    for (const width of [390,1440]) {
      const preview = await context.newPage();
      await preview.setViewportSize({width,height:width < 800 ? 844 : 1100});
      await preview.goto(baseURL);
      await preview.waitForFunction(() => document.querySelector('#statusText').textContent === 'Serveur actif');
      assert.equal(await preview.locator('#preferDirect').isChecked(), true);
      assert.equal(await preview.locator('#hw').isChecked(), true);
      await preview.screenshot({path:path.join(output, width === 390 ? 'pluxy-mobile-after.png' : 'pluxy-pc-after.png'),fullPage:true,animations:'disabled'});
      if (width === 1440) {
        await preview.setViewportSize({width,height:900});
        await preview.screenshot({path:path.join(output, 'pluxy-pc-overview.png'),animations:'disabled'});
      }
      await preview.close();
    }
    console.log(JSON.stringify({status:'passed',viewports:[320,390,780,1440],checks:['labels','preserve-settings','custom-selects','folder-picker','save-before-scan','pending-save','invalid-port','restart-address','failed-save','load-retry','javascript'],screenshots:output}));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
