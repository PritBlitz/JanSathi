// Browser smoke test for the built PWA (frontend/dist): starts the static server, drives headless Chrome over the
// DevTools protocol (no dependencies), walks the main flows and fails on console errors.
// Usage: node tools/smoke-web.mjs [--shots DIR] [--lang hi] [--scheme light|dark] [--url http://localhost:8080/ [--admin-token T]]
// Needs Chrome or Edge; set CHROME=/path/to/chrome if it is not found.
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const shots = args.includes('--shots') ? args[args.indexOf('--shots') + 1] : null;
const langArg = args.includes('--lang') ? args[args.indexOf('--lang') + 1] : 'en';
const schemeArg = args.includes('--scheme') ? args[args.indexOf('--scheme') + 1] : null; // light | dark
const PORT = 8765;
const urlArg = args.includes('--url') ? args[args.indexOf('--url') + 1] : null;
const BASE = urlArg || `http://localhost:${PORT}/`;
const DEBUG_PORT = 9333;
const CANDIDATES = [
  process.env.CHROME,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].filter(Boolean);
const chromePath = CANDIDATES.find((p) => existsSync(p));
if (!chromePath) {
  console.error('No Chrome found; set CHROME=/path/to/chrome');
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const server = urlArg ? { kill() {} } : spawn(process.execPath, [join(root, 'tools/serve.mjs'), String(PORT)], { stdio: 'ignore' });
const profileDir = mkdtempSync(join(tmpdir(), 'jansathi-chrome-'));
const chrome = spawn(chromePath, [
  '--headless=new', `--remote-debugging-port=${DEBUG_PORT}`, `--user-data-dir=${profileDir}`,
  // Ubuntu 24.04 runners block Chrome's sandbox; this only ever opens our own build.
  ...(process.env.CI ? ['--no-sandbox'] : []),
  '--no-first-run', '--no-default-browser-check', `--lang=${langArg}`, 'about:blank',
], { stdio: 'ignore' });

let ws;
let nextId = 1;
const pending = new Map();
const consoleErrors = [];
function send(method, params = {}, sessionId) {
  const id = nextId++;
  ws.send(JSON.stringify({ id, method, params, sessionId }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

async function connect() {
  for (let i = 0; i < 50; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`)).json();
      const pageTarget = list.find((t) => t.type === 'page');
      if (pageTarget) return pageTarget.webSocketDebuggerUrl;
    } catch {
      /* not up yet */
    }
    await sleep(200);
  }
  throw new Error('Chrome did not start');
}

const failures = [];
const check = (cond, msg) => {
  if (!cond) failures.push(msg);
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`);
};

try {
  ws = new WebSocket(await connect());
  await new Promise((r) => ws.addEventListener('open', r, { once: true }));
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
    } else if (msg.method === 'Runtime.exceptionThrown') {
      consoleErrors.push(msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text);
    } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
      consoleErrors.push(msg.params.args.map((a) => a.value ?? a.description).join(' '));
    } else if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
      consoleErrors.push(`${msg.params.entry.text} ${msg.params.entry.url || ''}`);
    }
  });
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Page.enable');
  if (schemeArg) await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: schemeArg }] });

  const evaluate = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  };
  const waitFor = async (expr, ms = 8000) => {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      if (await evaluate(`!!(${expr})`)) return true;
      await sleep(100);
    }
    return false;
  };
  const shot = async (name, width) => {
    if (!shots) return;
    await send('Emulation.setDeviceMetricsOverride', { width, height: width < 600 ? 780 : 900, deviceScaleFactor: 1, mobile: width < 600 });
    await sleep(400);
    const { data } = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    mkdirSync(shots, { recursive: true });
    writeFileSync(join(shots, `${name}-${width}.png`), Buffer.from(data, 'base64'));
  };
  const go = async (hash) => {
    await evaluate(`location.hash = ${JSON.stringify(hash)}`);
    await sleep(250);
  };

  // On overflow, name the widest offenders so the failure is actionable.
  const overflow = () =>
    evaluate(
      `(() => { if (document.documentElement.scrollWidth <= window.innerWidth) return ''; return [...document.querySelectorAll('body *')].filter((e) => e.getBoundingClientRect().right > window.innerWidth + 1 && !e.closest('.marquee, .rail, .filter-row, .footer-word')).slice(0, 4).map((e) => e.tagName.toLowerCase() + '.' + [...e.classList].join('.') + ' ' + Math.round(e.getBoundingClientRect().right)).join(' | '); })()`,
    );
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: BASE });
  check(await waitFor(`document.querySelectorAll('.index-row').length >= 5`), 'home renders the category index');
  if (langArg !== 'en') {
    await evaluate(`document.querySelector('.footer-langs button[lang="${langArg}"]').click()`);
    check(await waitFor(`document.documentElement.lang === '${langArg}'`), `language switches to ${langArg}`);
  }
  check(await waitFor(`document.querySelector('.hero-word').classList.contains('has-image')`), 'hero word gets the photo fill');
  check(await evaluate(`getComputedStyle(document.querySelector('.hero-word > span')).backgroundImage.includes('hero-band')`), 'the photo shows through the letters of the hero word');
  check(await evaluate(`!!document.querySelector('.hero-credit a[href*="commons.wikimedia.org"]')`), 'hero photo credits its author and licence');
  check(
    await evaluate(`!document.documentElement.dataset.theme && getComputedStyle(document.body).backgroundColor === 'rgb(251, 250, 246)'`),
    'light theme by default, whatever the device prefers',
  );
  // Preview mode (drafts, with a banner) only while nothing is published.
  const published = await evaluate(`fetch('data/bundle.json').then((r) => r.json()).then((b) => b.schemes.length)`);
  check(await evaluate(`!!document.querySelector('.banner')`) === (published === 0), `preview banner ${published ? 'is hidden (schemes are published)' : 'is shown (no published schemes yet)'}`);
  check(await evaluate(`document.querySelectorAll('.rail .rail-card').length >= 10`), 'rail lists the schemes');
  { const o = await overflow(); check(!o, `no horizontal scroll at 1280 ${o}`); }
  check(await evaluate(`!!document.querySelector('.marquee') && !!document.querySelector('.stats .stat-num') && !!document.querySelector('.how-steps li')`), 'home shows marquee, stats and how-it-works');
  await evaluate(`document.querySelector('.marquee-btn').click()`);
  check(await waitFor(`document.querySelector('.marquee').classList.contains('paused')`), 'marquee can be paused');
  await evaluate(`document.querySelector('.nav-tools > .icon-btn').click()`);
  check(await waitFor(`document.documentElement.dataset.theme === 'dark'`), 'theme button switches to dark');
  await evaluate(`localStorage.removeItem('jansathi.theme'); delete document.documentElement.dataset.theme`);
  await evaluate(`document.querySelector('.hero-ctas .btn-outline').click()`);
  check(await waitFor(`document.querySelector('dialog.sheet')?.open`), 'ask sheet opens');
  await evaluate(`(() => { const i = document.querySelector('#ask-input'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, 'pension'); i.dispatchEvent(new Event('input', { bubbles: true })); i.form.requestSubmit(); })()`);
  check(await waitFor(`document.querySelectorAll('dialog.sheet .scheme-card, dialog.sheet .passage-list li').length >= 2`), 'ask sheet finds pension schemes');
  await evaluate(`document.querySelector('dialog.sheet .sheet-head .icon-btn').click()`);
  check(await waitFor(`!document.querySelector('dialog.sheet').open`), 'ask sheet closes');
  await shot('home', 1280);
  await shot('home', 360);
  { const o = await overflow(); check(!o, `no horizontal scroll at 360 ${o}`); }
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

  // Questionnaire: answer the core questions with the first option, then keep answering until done.
  await evaluate(`localStorage.clear()`);
  await go('#/check');
  check(await waitFor(`document.querySelector('.q-title')`), 'questionnaire shows a question');
  await shot('question', 360);
  let steps = 0;
  while (steps < 40 && (await evaluate(`!!document.querySelector('.option')`))) {
    // Pick a varied but deterministic answer: second option when there is one.
    await evaluate(`(document.querySelectorAll('.option')[1] || document.querySelector('.option')).click()`);
    await sleep(60);
    if (await evaluate(`!!document.querySelector('#exact-input')`)) {
      await evaluate(`(() => { const i = document.querySelector('#exact-input'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, i.min); i.dispatchEvent(new Event('input', { bubbles: true })); i.form.requestSubmit(); })()`);
      await sleep(60);
    }
    steps++;
  }
  check(steps > 4 && steps < 40, `questionnaire ends after ${steps} answers`);
  await go('#/results');
  const total = published || (await evaluate(`fetch('data/bundle.preview.json').then((r) => r.json()).then((b) => b.schemes.length)`));
  check(await waitFor(`document.querySelectorAll('.results-group .scheme-card').length === ${total}`), `results show all ${total} schemes`);
  check(await evaluate(`document.querySelectorAll('.trace li').length > 10`), 'results show rule traces');
  await shot('results', 1280);

  await go(`#/scheme/${published ? 'pm-kisan' : 'igndps'}`);
  check(await waitFor(`document.querySelectorAll('.source-card').length >= 2`), 'scheme detail lists sources');
  check(await evaluate(`!!document.querySelector('.warn')`), 'scheme detail says who checked it (draft or AI-review notice)');
  await evaluate(`document.querySelector('.panel-docs .btn-block').click()`);
  await shot('detail', 1280);
  await go('#/checklist');
  check(await waitFor(`document.querySelectorAll('.check-item').length >= 3`), 'checklist lists documents');
  await go('#/slip');
  check(await waitFor(`document.querySelectorAll('.slip-docs li').length >= 3 && document.querySelectorAll('.slip-list li').length >= 4`), 'office slip lists schemes and documents');
  await shot('slip', 360);

  // Family mode: a second member shares household answers but answers member questions afresh.
  await go('#/family');
  check(await waitFor(`document.querySelectorAll('.member-card').length === 1`), 'family page shows one member');
  await evaluate(`(() => { const i = document.querySelector('#member-name'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, 'Mother'); i.dispatchEvent(new Event('input', { bubbles: true })); i.form.requestSubmit(); })()`);
  check(await waitFor(`document.querySelectorAll('.member-card').length === 2`), 'a family member can be added');
  const shared = await evaluate(`(() => { const p = JSON.parse(localStorage.getItem('jansathi.profile')); return [p.members.length, 'state' in p.members[1].profile || 'area' in p.members[1].profile, 'age' in p.members[1].profile]; })()`);
  check(shared[0] === 2 && shared[1] === true && shared[2] === false, 'new member gets household answers, not member answers');
  await go('#/check');
  check(await waitFor(`!!document.querySelector('.member-tag')`), 'questionnaire says whose answers these are');
  await shot('family-question', 360);
  await go('#/family');
  await shot('family', 1280);
  await evaluate(`[...document.querySelectorAll('.member-card .btn-ghost')][0].click()`);
  check(await waitFor(`document.querySelectorAll('.member-card').length === 1`), 'a family member can be removed');
  await go('#/checklist');
  await waitFor(`document.querySelectorAll('.check-item').length >= 3`);
  await evaluate(`document.querySelector('.seg button:nth-child(2)').click()`);
  check(await waitFor(`!!document.querySelector('details[open]')`), "'don't have' opens how-to-get-it");
  // Tracking: a status set on the scheme page shows in My applications, with a nav entry.
  await go('#/scheme/pm-kisan');
  check(await waitFor(`!!document.querySelector('.panel-when p')`), 'scheme page says when to apply');
  await waitFor(`!!document.querySelector('#track-pm-kisan')`);
  await evaluate(`(() => { const s = document.querySelector('#track-pm-kisan'); Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(s, 'rejected'); s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  check(await waitFor(`!!document.querySelector('#track-pm-kisan-why')`), 'a rejected application asks for the reason');
  await go('#/applications');
  check(await waitFor(`document.querySelectorAll('.application-list .check-item').length >= 1 && !!document.querySelector('a[href="#/applications"]')`), 'My applications lists the tracked scheme');

  // Reminders: switching on is remembered on the device. (Headless Chrome denies notifications.)
  await go('#/checklist');
  await waitFor(`!!document.querySelector('.reminder-switch input')`);
  await evaluate(`document.querySelector('.reminder-switch input').click()`);
  check(await waitFor(`JSON.parse(localStorage.getItem('jansathi.reminders') || '{}').enabled === true`), 'reminders can be switched on');

  // Photo check: real OCR in the browser on a page drawn here, so nothing depends on a sample file.
  await evaluate(`(async () => {
    const c = document.createElement('canvas'); c.width = 1200; c.height = 500;
    const g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, 1200, 500); g.fillStyle = '#000'; g.font = '44px serif';
    ['INCOME CERTIFICATE', 'This is to certify that the annual', 'income of the family is shown below', 'as recorded by the Tehsil office'].forEach((l, i) => g.fillText(l, 60, 100 + i * 90));
    const blob = await new Promise((r) => c.toBlob(r, 'image/png'));
    const input = document.querySelector('[data-doc-check="income_certificate"], [data-doc-check]');
    window.__ocrCode = input.dataset.docCheck;
    const dt = new DataTransfer(); dt.items.add(new File([blob], 'page.png', { type: 'image/png' })); input.files = dt.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  check(await waitFor(`['looks_right', 'cannot_tell'].includes(document.querySelector('[data-verdict]')?.dataset.verdict)`, 90000), 'photo check reads an image on the device and gives a verdict');
  check(await evaluate(`!document.querySelector('.doc-check').innerText.toLowerCase().includes('tehsil')`), 'photo check never shows the text it read');
  check(await evaluate(`document.querySelector('.doc-check').innerText.includes('valid') || document.documentElement.lang !== 'en'`), 'photo check says it does not prove a document is valid');

  // Nearby help: a fixed position and a stubbed map service, so the run needs no third party.
  await go('#/help');
  await waitFor(`!!document.querySelector('a[href*="locator.csccloud.in"]')`);
  check(await evaluate(`performance.getEntriesByType('resource').every((r) => !/overpass|openstreetmap/.test(r.name))`), 'nearby help contacts no map service before the person asks');
  await evaluate(`(() => {
    navigator.geolocation.getCurrentPosition = (ok) => ok({ coords: { latitude: 28.61394, longitude: 77.20902 } });
    const real = window.fetch;
    window.fetch = (u, o) => {
      if (!String(u).includes('overpass')) return real(u, o);
      window.__overpassQuery = String(o.body);
      return Promise.resolve(new Response(JSON.stringify({ elements: [{ type: 'node', id: 1, lat: 28.62, lon: 77.22, tags: { name: '<b>Test</b> CSC', amenity: 'townhall' } }] })));
    };
    document.querySelector('.page-head .actions button').click();
  })()`);
  check(await waitFor(`document.querySelectorAll('.nearby-list li').length === 1`), 'nearby help lists places');
  check(await evaluate(`decodeURIComponent(window.__overpassQuery).includes('28.61,77.21') && !decodeURIComponent(window.__overpassQuery).includes('28.613')`), 'nearby help sends only a rounded location');
  check(await waitFor(`!!document.querySelector('.nearby-map .leaflet-container, .nearby-map.leaflet-container')`), 'nearby help draws the map');
  check(await evaluate(`!document.querySelector('.nearby-list b') && document.querySelector('.nearby-list strong').textContent.includes('<b>')`), 'place names are shown as text, not HTML');

  await go('#/search?q=pension');
  check(await waitFor(`document.querySelectorAll('.scheme-card').length >= 3`), 'search finds pension schemes');
  await go('#/search?q=zzzzqqq');
  check(await waitFor(`!!document.querySelector('.warn')`), 'search with no match says no official source');
  await go('#/privacy');
  await waitFor(`!!document.querySelector('.privacy-points')`);
  await evaluate(`window.confirm = () => true; document.querySelector('.page .btn-primary').click()`);
  check(await waitFor(`!localStorage.getItem('jansathi.profile') && !localStorage.getItem('jansathi.checklist') && !localStorage.getItem('jansathi.reminders') && !localStorage.getItem('jansathi.applications')`), 'delete my data clears storage');

  // Hero width-fit per locale (DESIGN §6.5): the word should fill 80-100% of the content width.
  await go('#/');
  const fits = [];
  for (const code of ['en', 'hi', 'bn', 'ta', 'te', 'mr', 'gu', 'kn', 'ml', 'pa']) {
    await evaluate(`document.querySelector('.footer-langs button[lang="${code}"]').click()`);
    await waitFor(`document.documentElement.lang === '${code}'`);
    await sleep(300);
    const ratio = await evaluate(`(() => { const p = document.querySelector('.hero-word-wrap'); const cs = getComputedStyle(p); const s = p.querySelector('.hero-word > span'); return s.getBoundingClientRect().width / (p.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)); })()`);
    fits.push(`${code} ${ratio.toFixed(2)}`);
    check(ratio > 0.8 && ratio <= 1.0, `hero word fills ${Math.round(ratio * 100)}% of the width in ${code}`);
  }
  console.log(`hero fill ratios: ${fits.join(', ')}`);
  await evaluate(`document.querySelector('.footer-langs button[lang="en"]').click()`);
  await waitFor(`document.documentElement.lang === 'en'`);

  // Accounts, Saathi and the helper portal: only against a running API (--url) with --admin-token.
  const adminToken = args.includes('--admin-token') ? args[args.indexOf('--admin-token') + 1] : null;
  if (urlArg && adminToken) {
    const setInput = (sel, val) =>
      `(() => { const i = document.querySelector('${sel}'); Object.getOwnPropertyDescriptor(Object.getPrototypeOf(i), 'value').set.call(i, ${JSON.stringify(val)}); i.dispatchEvent(new Event('input', { bubbles: true })); })()`;
    const signIn = async (phone) => {
      await go('#/login?next=%23%2Fguide');
      await waitFor(`!!document.querySelector('#phone')`);
      await evaluate(setInput('#phone', phone));
      await evaluate(`document.querySelector('#phone').form.requestSubmit()`);
      // The dev SMS stand-in shows the code; without it (or when rate-limited) sign-in can't go on.
      if (!(await waitFor(`!!document.querySelector('.auth-form .warn')`))) return false;
      const code = await evaluate(`document.querySelector('.auth-form .warn').textContent.match(/\\d{6}/)[0]`);
      await evaluate(setInput('#code', code));
      await evaluate(`document.querySelector('#code').form.requestSubmit()`);
      return waitFor(`location.hash.startsWith('#/guide')`);
    };
    const signOut = async () => {
      await go('#/account');
      await waitFor(`!!document.querySelector('.account-page')`);
      await evaluate(`[...document.querySelectorAll('.account-page .btn-outline')].find((b) => b.closest('.page-head')).click()`);
      await waitFor(`!localStorage.getItem('jansathi.session')`);
    };
    const person = `98765${String(Date.now()).slice(-5)}`;
    const helperPhone = `98764${String(Date.now()).slice(-5)}`;

    check(await signIn(person), 'person signs in with a one-time code');
    const asked = await waitFor(`document.querySelectorAll('.question-card .quick-replies button').length >= 2`);
    const pageText = asked ? '' : await evaluate(`(document.querySelector('main')?.innerText || '').slice(0, 300).replace(/\\s+/g, ' ')`);
    check(asked, `Saathi asks the first question with tap-to-answer options ${pageText} ${asked ? '' : consoleErrors.join(' | ')}`);
    await evaluate(`document.querySelectorAll('.question-card .quick-replies button')[1].click()`);
    check(await waitFor(`!!document.querySelector('.saved-chip')`), 'a tapped answer is saved and shown with undo');
    await shot('guide', 360);

    await go('#/account');
    await waitFor(`!!document.querySelector('.share-scopes')`);
    await evaluate(`[...document.querySelectorAll('#share-title ~ .actions .btn-primary, .account-section .actions .btn-primary')].pop().click()`);
    check(await waitFor(`/^[A-Z2-9]{6}$/.test(document.querySelector('.share-code-value')?.textContent || '')`), 'person makes a share code');
    const shareCode = await evaluate(`document.querySelector('.share-code-value').textContent`);
    await shot('account', 1280);
    await signOut();

    const reg = await evaluate(`fetch('api/v1/admin/volunteers', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ${adminToken}' }, body: JSON.stringify({ phone: '${helperPhone}', name: 'Smoke Helper', organisation: 'Test CSC' }) }).then((r) => r.status)`);
    check(reg === 200, 'admin registers a helper');
    check(await signIn(helperPhone), 'helper signs in');
    await go('#/helper');
    await waitFor(`!!document.querySelector('#helper-code')`);
    await evaluate(setInput('#helper-code', shareCode));
    await evaluate(`document.querySelector('#helper-code').form.requestSubmit()`);
    check(await waitFor(`location.hash.startsWith('#/helper/') && document.querySelectorAll('.answer-list > div').length >= 1`), 'helper opens the case by code and sees the answers');
    await shot('helper-case', 1280);
    await signOut();

    await signIn(person);
    await go('#/account');
    check(await waitFor(`[...document.querySelectorAll('.audit-list li')].some((li) => li.textContent.includes('Smoke Helper'))`), "the person sees the helper's visit in their audit log");
  }

  // Accessibility: axe on each page, both themes on home. Any serious or critical issue fails.
  const axeSource = readFileSync(join(root, 'frontend/node_modules/axe-core/axe.min.js'), 'utf8');
  await evaluate(axeSource + ';0');
  const axeRun = async (name) => {
    const found = await evaluate(`axe.run(document, { resultTypes: ['violations'] }).then((r) => r.violations.filter((v) => ['serious', 'critical'].includes(v.impact)).map((v) => v.id + ' x' + v.nodes.length + ' ' + v.nodes[0].target.join(' ')))`);
    check(found.length === 0, `axe finds no serious issues on ${name} ${found.join(' | ')}`);
  };
  for (const [hash, ready] of [['#/', '.hero-word'], ['#/check', '.q-title'], ['#/schemes', '.scheme-card'], ['#/scheme/pm-kisan', '.panel-when'], ['#/checklist', '.page-head'], ['#/applications', '.page-head'], ['#/help', '.page-head'], ['#/family', '.member-card'], ['#/privacy', '.privacy-points'], ['#/grievance', '.page-head']]) {
    await go(hash);
    await waitFor(`!!document.querySelector('${ready}')`);
    await sleep(900); // scroll reveals and colour transitions settle
    await evaluate(`document.querySelectorAll('.reveal, [data-reveal]').forEach((e) => e.classList.add('in', 'is-in', 'is-visible'))`);
    await axeRun(hash);
  }
  await go('#/');
  await evaluate(`document.documentElement.dataset.theme = 'dark'`);
  await sleep(600);
  await axeRun('#/ (dark)');
  await evaluate(`delete document.documentElement.dataset.theme`);

  // A key on screen means a missing translation (t() falls back to the key itself).
  const untranslated = await evaluate(`fetch('locales/en.json').then((r) => r.json()).then((en) => { const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); const out = []; while (w.nextNode()) { const s = w.currentNode.textContent.trim(); if (s && s in en) out.push(s); } return out; })`);
  check(untranslated.length === 0, `no raw i18n keys on screen ${untranslated.join(', ')}`);
  // Tesseract's own engine prints these for parameters its language files don't use; harmless.
  const realErrors = consoleErrors.filter((e) => !/^Warning: Parameter not found: \w+ .*\/ocr\/tesseract-core/.test(e));
  check(realErrors.length === 0, `no console errors${realErrors.length ? ': ' + realErrors.join(' | ') : ''}`);
} catch (err) {
  failures.push(err.message);
  console.error(err);
} finally {
  try {
    ws?.close();
  } catch {
    /* ignore */
  }
  chrome.kill();
  server.kill();
  // Chrome holds the profile until it exits; then delete it, or every run leaves ~50 MB in the temp folder.
  await new Promise((resolve) => (chrome.exitCode !== null ? resolve() : chrome.once('exit', resolve)));
  rmSync(profileDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}
console.log(failures.length ? `\n${failures.length} failure(s)` : '\nsmoke test passed');
process.exit(failures.length ? 1 : 0);
