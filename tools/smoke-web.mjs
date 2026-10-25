// Browser smoke test for web/: starts the static server, drives headless Chrome over the
// DevTools protocol (no dependencies), walks the main flows and fails on console errors.
// Usage: node tools/smoke-web.mjs [--shots DIR] [--lang hi]
// Needs Chrome or Edge; set CHROME=/path/to/chrome if it is not found.
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync, mkdtempSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const shots = args.includes('--shots') ? args[args.indexOf('--shots') + 1] : null;
const langArg = args.includes('--lang') ? args[args.indexOf('--lang') + 1] : 'en';
const PORT = 8765;
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
const server = spawn(process.execPath, [join(root, 'tools/serve.mjs'), String(PORT)], { stdio: 'ignore' });
const profileDir = mkdtempSync(join(tmpdir(), 'jansathi-chrome-'));
const chrome = spawn(chromePath, [
  '--headless=new', `--remote-debugging-port=${DEBUG_PORT}`, `--user-data-dir=${profileDir}`,
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

  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: `http://localhost:${PORT}/` });
  check(await waitFor(`document.querySelector('#tiles .tile')`), 'home renders category tiles');
  if (langArg !== 'en') {
    await evaluate(`document.querySelector('[data-lang="${langArg}"]').click()`);
    check(await waitFor(`document.documentElement.lang === '${langArg}'`), `language switches to ${langArg}`);
  }
  check(await waitFor(`document.querySelector('.hero-word').classList.contains('has-image')`), 'hero word gets the collage fill');
  check(await evaluate(`getComputedStyle(document.querySelector('.hero-word > span')).backgroundImage.includes('collage')`), 'collage is the background of the hero word');
  check(await evaluate(`!document.querySelector('#preview-banner').hidden`), 'preview banner is shown (no published schemes yet)');
  check(await evaluate(`document.querySelectorAll('#rail .card-link').length >= 10`), 'rail lists the draft schemes');
  check(await evaluate(`document.documentElement.scrollWidth <= window.innerWidth`), 'no horizontal scroll at 1280');
  await shot('home', 1280);
  await shot('home', 360);
  check(await evaluate(`document.documentElement.scrollWidth <= window.innerWidth`), 'no horizontal scroll at 360');
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

  // Questionnaire: answer the core questions with the first option, then keep answering until done.
  await evaluate(`localStorage.clear()`);
  await go('#/check');
  check(await waitFor(`document.querySelector('#q-title')`), 'questionnaire shows a question');
  await shot('question', 360);
  let steps = 0;
  while (steps < 40 && (await evaluate(`!!document.querySelector('[data-answer]')`))) {
    // Pick a varied but deterministic answer: second option when there is one.
    await evaluate(`(document.querySelectorAll('[data-answer]')[1] || document.querySelector('[data-answer]')).click()`);
    await sleep(60);
    if (await evaluate(`!!document.querySelector('#exact-input')`)) {
      await evaluate(`(() => { const i = document.querySelector('#exact-input'); i.value = i.min; i.form.requestSubmit(); })()`);
      await sleep(60);
    }
    steps++;
  }
  check(steps > 4 && steps < 40, `questionnaire ends after ${steps} answers`);
  await go('#/results');
  const total = await evaluate(`fetch('data/bundle.preview.json').then((r) => r.json()).then((b) => b.schemes.length)`);
  check(await waitFor(`document.querySelectorAll('.results-group .card').length === ${total}`), `results show all ${total} schemes`);
  check(await evaluate(`document.querySelectorAll('.trace li').length > 10`), 'results show rule traces');
  await shot('results', 1280);

  await go('#/scheme/igndps');
  check(await waitFor(`document.querySelectorAll('.source-card').length >= 2`), 'scheme detail lists sources');
  check(await evaluate(`!!document.querySelector('.warn')`), 'scheme detail shows the draft warning');
  await evaluate(`document.querySelector('[data-action="add-checklist"]').click()`);
  await shot('detail', 1280);
  await go('#/checklist');
  check(await waitFor(`document.querySelectorAll('.check-item').length >= 3`), 'checklist lists documents');
  await evaluate(`document.querySelector('[data-have][data-v="0"]').click()`);
  check(await evaluate(`!!document.querySelector('details[open]')`), "'don't have' opens how-to-get-it");
  await go('#/search?q=pension');
  check(await waitFor(`document.querySelectorAll('.card').length >= 3`), 'search finds pension schemes');
  await go('#/search?q=zzzzqqq');
  check(await waitFor(`!!document.querySelector('.warn')`), 'search with no match says no official source');
  await go('#/privacy');
  await evaluate(`window.confirm = () => true; document.querySelector('[data-action="delete-data"]').click()`);
  check(await evaluate(`!localStorage.getItem('jansathi.profile') && !localStorage.getItem('jansathi.checklist')`), 'delete my data clears storage');

  // Hero width-fit per locale (DESIGN §6.5): the word should fill 85-100% of the content width.
  await go('#/');
  const fits = [];
  for (const code of ['en', 'hi', 'bn', 'ta', 'te', 'mr', 'gu', 'kn', 'ml', 'pa']) {
    await evaluate(`document.querySelector('[data-lang="${code}"]').click()`);
    await waitFor(`document.documentElement.lang === '${code}'`);
    await sleep(300);
    const ratio = await evaluate(`(() => { const w = document.querySelector('.hero-word'); const s = w.firstElementChild; return s.getBoundingClientRect().width / w.parentElement.clientWidth; })()`);
    fits.push(`${code} ${ratio.toFixed(2)}`);
    check(ratio > 0.8 && ratio <= 1.0, `hero word fills ${Math.round(ratio * 100)}% of the width in ${code}`);
  }
  console.log(`hero fill ratios: ${fits.join(', ')}`);

  const untranslated = await evaluate(`[...document.querySelectorAll('[data-i18n]')].filter(e => e.textContent === e.dataset.i18n).map(e => e.dataset.i18n)`);
  check(untranslated.length === 0, `no raw i18n keys on screen ${untranslated.join(', ')}`);
  check(consoleErrors.length === 0, `no console errors${consoleErrors.length ? ': ' + consoleErrors.join(' | ') : ''}`);
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
}
console.log(failures.length ? `\n${failures.length} failure(s)` : '\nsmoke test passed');
process.exit(failures.length ? 1 : 0);
