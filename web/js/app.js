// JanSathi PWA wiring: bundle loading, hash router and views.
// The rules engine (rules.js) decides every result; this file only displays it.
import { LANGS, t, apply, setLang, detectLang, lang, speechTag, langName, formatDate, formatNumber, pick, store } from './i18n.js';
import { speak, stop, listen, hasVoice, canSpeak, canListen, onSpeakingChange } from './speech.js';
import { match, toBand } from './rules.js';

const KEYS = { profile: 'jansathi.profile', checklist: 'jansathi.checklist', motion: 'jansathi.motion', preview: 'jansathi.preview' };
const CATEGORY_ICONS = {
  pension: '👵', agriculture: '🌾', health: '🏥', housing: '🏠', education: '🎓', women_children: '🤱', disability: '♿',
  insurance: '🛡️', employment: '⛏️', energy: '🔥', family_support: '🤝', food: '🍚',
};
const REPORT_URL = 'https://github.com/PritBlitz/JanSathi/issues/new';

const state = {
  bundle: null,
  preview: false,
  profile: {},
  skipped: [],
  history: [],
  checklist: { schemes: [], have: {} },
  voiceMode: false,
};

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const today = () => new Date().toISOString().slice(0, 10);

// ---------- persistence ----------
function loadState() {
  try {
    const p = JSON.parse(store.get(KEYS.profile) || 'null');
    if (p && typeof p === 'object') {
      state.profile = p.profile || {};
      state.skipped = p.skipped || [];
      state.history = p.history || [];
    }
    const c = JSON.parse(store.get(KEYS.checklist) || 'null');
    if (c && typeof c === 'object') state.checklist = { schemes: c.schemes || [], have: c.have || {} };
  } catch {
    /* corrupt storage: start fresh */
  }
}
const saveProfile = () => store.set(KEYS.profile, JSON.stringify({ profile: state.profile, skipped: state.skipped, history: state.history }));
const saveChecklist = () => store.set(KEYS.checklist, JSON.stringify(state.checklist));

function deleteAllData() {
  for (const k of Object.values(KEYS)) store.remove(k);
  store.remove('jansathi.lang');
  state.profile = {};
  state.skipped = [];
  state.history = [];
  state.checklist = { schemes: [], have: {} };
}

// ---------- bundle ----------
async function fetchJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return res.json();
}

async function loadBundle() {
  const wantPreview = new URLSearchParams(location.search).has('preview') || store.get(KEYS.preview) === '1';
  let bundle = null;
  if (!wantPreview) {
    try {
      bundle = await fetchJson('data/bundle.json');
    } catch {
      bundle = null;
    }
  }
  // Until a reviewer publishes schemes, the production bundle is empty: fall back to the
  // preview bundle and say so on every page.
  if (!bundle || !bundle.schemes.length) {
    bundle = await fetchJson('data/bundle.preview.json');
    state.preview = true;
  }
  state.bundle = bundle;
  $('#preview-banner').hidden = !state.preview;
}

const fields = () => state.bundle.fields.fields;
const schemeBy = (slug) => state.bundle.schemes.find((s) => s.slug === slug);
const runMatch = () => match(state.bundle, state.profile, { skipped: state.skipped, as_of: today() });

// ---------- notices and speech ----------
let noticeTimer;
function notice(msg, ms = 6000) {
  const el = $('#notice');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => (el.hidden = true), ms);
}

function say(text) {
  if (!canSpeak()) {
    notice(t('speech.unsupported'));
    return false;
  }
  if (!speak(text, speechTag())) {
    notice(t('speech.no_voice', { language: langName() }));
    return false;
  }
  return true;
}

function readableText(root) {
  const clone = root.cloneNode(true);
  clone.querySelectorAll('[aria-hidden="true"], .visually-hidden, button, select, input, .rail-btns, script, style').forEach((n) => n.remove());
  return clone.textContent.replace(/\s+/g, ' ').trim();
}

// ---------- labels ----------
const fieldQuestion = (f) => t(`q.${f}`);
function optionLabel(field, value) {
  const def = fields()[field];
  if (def?.type === 'bool') return t(value ? 'opt.bool.yes' : 'opt.bool.no');
  return t(`opt.${field}.${value}`);
}
function answerLabel(field) {
  const def = fields()[field];
  const v = state.profile[field];
  if (v === undefined) return '';
  if (def.type === 'band') {
    const b = toBand(v);
    const opt = def.options.find((o) => o.band && o.band.min === b.min && o.band.max === b.max);
    return opt ? optionLabel(field, opt.value) : formatNumber(b.min);
  }
  return optionLabel(field, v);
}
const categoryLabel = (c) => t(`cat.${c}`);
const resultKey = { LIKELY_ELIGIBLE: 'likely', NEED_MORE_INFO: 'need_info', NOT_ELIGIBLE: 'not_eligible' };
const badgeClass = { LIKELY_ELIGIBLE: 'badge-likely', NEED_MORE_INFO: 'badge-need', NOT_ELIGIBLE: 'badge-not' };
const badgeIcon = { LIKELY_ELIGIBLE: '✓', NEED_MORE_INFO: '?', NOT_ELIGIBLE: '–' };
const markIcon = { pass: '✓', fail: '–', unknown: '?' };

function badge(result) {
  return `<span class="badge ${badgeClass[result]}"><span aria-hidden="true">${badgeIcon[result]}</span> ${esc(t(`result.${resultKey[result]}`))}</span>`;
}
function trustChip(label) {
  const key = label === 'uncertain' ? 'trust.unverified' : `trust.${label}`;
  const icon = label === 'confirmed' ? '🛡️' : label === 'guidance' ? '📄' : '⚠️';
  return `<span class="chip chip-${esc(label)}"><span aria-hidden="true">${icon}</span> ${esc(t(key))}</span>`;
}
function schemeText(s, key) {
  const c = s.content[lang()] ? s.content[lang()] : s.content.en;
  return c[key];
}
const inEnglish = (s) => !s.content[lang()];
const langAttr = (s) => (inEnglish(s) ? ' lang="en"' : '');

// ---------- router ----------
const routes = {
  '': renderHome,
  how: renderHome,
  check: renderQuestion,
  results: renderResults,
  scheme: renderScheme,
  schemes: renderSchemes,
  checklist: renderChecklist,
  search: renderSearch,
  help: renderHelp,
  privacy: renderPrivacy,
};

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, query = ''] = raw.split('?');
  const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
  return { name: parts[0] || '', arg: parts[1], query: new URLSearchParams(query) };
}

function render() {
  stop();
  const route = parseHash();
  const fn = routes[route.name] || renderHome;
  const isHome = fn === renderHome;
  $('#home').hidden = !isHome;
  $('#view').hidden = isHome;
  $$('#nav-links a').forEach((a) => a.toggleAttribute('aria-current', a.getAttribute('href') === `#/${route.name}`));
  closeMenu();
  fn(route);
  if (!isHome) {
    apply($('#view'));
    $('#main').focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }
  observeReveals();
}

function page(html) {
  $('#view').innerHTML = `<div class="page">${html}</div>`;
  apply($('#view'));
}

// ---------- home ----------
function renderHome(route) {
  renderRail();
  renderTiles();
  if (route?.name === 'how') requestAnimationFrame(() => $('#how').scrollIntoView());
}

function renderRail() {
  const wrap = $('#rail-wrap');
  // Published schemes have verified sources. In preview mode, drafts are shown with their warning.
  const list = state.bundle.schemes.filter((s) => state.preview || s.status === 'published');
  wrap.hidden = !list.length;
  if (!list.length) return;
  $('#rail-title').textContent = t(state.preview ? 'rail.title' : 'rail.published');
  $('#rail').innerHTML = list
    .map((s) => {
      const name = schemeText(s, 'name');
      return `<li><a class="card-link" href="#/scheme/${esc(s.slug)}">
        <span class="avatar" aria-hidden="true">${esc(s.icon)}</span>
        <span><span class="card-kicker">${esc(categoryLabel(s.category))}</span>
          <span class="card-title"${langAttr(s)}>${esc(name)}</span>
          ${s.status !== 'published' ? `<span class="small muted">${esc(t('preview.badge'))}</span>` : ''}</span>
        <span class="icon-btn" aria-hidden="true"><span>↗</span></span></a></li>`;
    })
    .join('');
}

function renderTiles() {
  const cats = [...new Set(state.bundle.schemes.map((s) => s.category))];
  $('#tiles').innerHTML = cats
    .map((c) => `<li><a class="tile" href="#/schemes/${esc(c)}"><span class="tile-icon" aria-hidden="true">${CATEGORY_ICONS[c] || '📌'}</span><span>${esc(categoryLabel(c))}</span></a></li>`)
    .join('');
}

// ---------- questionnaire ----------
function currentQuestion() {
  const core = state.bundle.fields.core.find((f) => !(f in state.profile) && !state.skipped.includes(f));
  if (core) return { field: core, exact: false };
  const m = runMatch();
  if (!m.next_question) return null;
  const f = m.next_question;
  return { field: f, exact: f in state.profile && fields()[f].type === 'band' };
}

function remainingEstimate() {
  const m = runMatch();
  const set = new Set();
  for (const r of m.results) if (r.result === 'NEED_MORE_INFO') r.missing_fields.forEach((f) => !state.skipped.includes(f) && set.add(f));
  state.bundle.fields.core.forEach((f) => !(f in state.profile) && !state.skipped.includes(f) && set.add(f));
  return set.size;
}

function renderQuestion() {
  const q = currentQuestion();
  const answered = state.history.length;
  if (!q) {
    page(`<div class="q-wrap">
      <h1 class="h1" data-i18n="q.done_title"></h1>
      <p class="hero-lead" style="margin-inline:0" data-i18n="q.done_body"></p>
      <div class="hero-ctas" style="justify-content:flex-start">
        <a class="btn btn-primary btn-hero" href="#/results" data-i18n="q.see_results"></a>
        <button class="btn btn-outline" type="button" data-action="back" ${answered ? '' : 'hidden'} data-i18n="q.back"></button>
      </div></div>`);
    return;
  }
  const def = fields()[q.field];
  const pct = Math.round((answered / (answered + remainingEstimate() || 1)) * 100);
  const title = q.exact ? t(`q.exact.${q.field}`) : fieldQuestion(q.field);
  let body;
  if (q.exact) {
    const b = toBand(state.profile[q.field]);
    body = `<p class="q-help">${esc(t('q.exact_why'))}</p>
      <form class="exact" data-form="exact">
        <label class="visually-hidden" for="exact-input">${esc(title)}</label>
        <input id="exact-input" type="number" inputmode="numeric" min="${b.min}" max="${b.max}" required placeholder="${esc(formatNumber(b.min))} – ${esc(formatNumber(b.max))}">
        <button class="btn btn-primary" type="submit">${esc(t('q.exact_submit'))}</button>
      </form>`;
  } else {
    const opts =
      def.type === 'bool'
        ? [{ value: true, label: t('opt.bool.yes'), icon: '✅' }, { value: false, label: t('opt.bool.no'), icon: '❌' }, { value: null, label: t('opt.bool.dont_know'), icon: '🤷' }]
        : def.options.map((o) => ({ value: o.value, label: t(`opt.${q.field}.${o.value}`), icon: o.icon }));
    const helpKey = `help.${q.field}`;
    body = `${t(helpKey) !== helpKey ? `<p class="q-help">${esc(t(helpKey))}</p>` : ''}
      <ul class="options">${opts
        .map((o, i) => `<li><button class="tile" type="button" data-answer="${i}"><span class="tile-icon" aria-hidden="true">${esc(o.icon || '')}</span><span>${esc(o.label)}</span></button></li>`)
        .join('')}</ul>`;
    state._opts = opts;
  }
  state._q = q;
  page(`<div class="q-wrap">
    <div class="q-top"><p class="kicker">${esc(t('q.kicker', { n: formatNumber(answered + 1) }))}</p>
      <a class="btn btn-quiet" href="#/results">${esc(t('q.see_results'))}</a></div>
    <div class="progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${pct}" aria-label="${esc(t('q.kicker', { n: formatNumber(answered + 1) }))}"><span style="width:${pct}%"></span></div>
    <h1 class="q-title" id="q-title" tabindex="-1">${esc(title)}</h1>
    <div class="q-tools">
      <button class="btn btn-outline" type="button" data-action="speak-q"><span aria-hidden="true">🔊</span> ${esc(t('q.speak'))}</button>
      ${!q.exact && canListen() ? `<button class="btn btn-outline" type="button" data-action="mic-q"><span aria-hidden="true">🎤</span> ${esc(t('q.mic'))}</button>` : ''}
    </div>
    <p class="small" role="status" aria-live="polite" id="q-status"></p>
    ${body}
    <div class="q-foot">
      <button class="btn btn-outline" type="button" data-action="back" ${answered ? '' : 'disabled'}>${esc(t('q.back'))}</button>
      <button class="btn btn-quiet" type="button" data-action="skip">${esc(t('q.skip'))}</button>
    </div>
    <p class="privacy-note">${esc(t('q.privacy_note'))}</p></div>`);
  if (state.voiceMode) speakQuestion();
}

function questionSpeech() {
  const title = $('#q-title')?.textContent || '';
  const opts = (state._q && !state._q.exact ? state._opts.map((o) => o.label) : []).join('. ');
  return `${title} ${opts}`;
}
function speakQuestion() {
  say(questionSpeech());
}

function answer(field, value, { exact = false } = {}) {
  const def = fields()[field];
  const entry = { field, prev: state.profile[field], wasSkipped: state.skipped.includes(field) };
  state.skipped = state.skipped.filter((f) => f !== field);
  if (value === null || value === undefined) {
    delete state.profile[field];
    state.skipped.push(field);
  } else if (def.type === 'band' && !exact) {
    const opt = def.options.find((o) => o.value === value);
    if (opt.unknown) {
      delete state.profile[field];
      state.skipped.push(field);
    } else state.profile[field] = { ...opt.band };
  } else if (def.type === 'enum') {
    const opt = def.options.find((o) => o.value === value);
    if (opt?.unknown) {
      delete state.profile[field];
      state.skipped.push(field);
    } else state.profile[field] = value;
  } else {
    state.profile[field] = value;
  }
  state.history.push(entry);
  saveProfile();
  renderQuestion();
  $('#q-title')?.focus();
}

function skipCurrent() {
  const q = state._q;
  if (!q) return;
  if (!state.skipped.includes(q.field)) state.skipped.push(q.field);
  // A straddling band stays as the user's range; the engine just stops asking for it.
  state.history.push({ field: q.field, prev: state.profile[q.field], wasSkipped: false });
  saveProfile();
  renderQuestion();
}

function goBack() {
  const last = state.history.pop();
  if (!last) return;
  state.skipped = state.skipped.filter((f) => f !== last.field);
  if (last.wasSkipped) state.skipped.push(last.field);
  if (last.prev === undefined) delete state.profile[last.field];
  else state.profile[last.field] = last.prev;
  saveProfile();
  renderQuestion();
}

// Keeps combining marks (\p{M}): Indic vowel signs are marks, not letters.
const normalise = (s) => String(s).toLowerCase().normalize('NFC').replace(/[^\p{L}\p{M}\p{N}\s]/gu, ' ').replace(/\s+/g, ' ').trim();

/** Index of the option whose label words all appear as whole words in what was heard, or -1. */
function matchOption(heard, labels) {
  const said = new Set(normalise(heard).split(' '));
  let best = -1;
  let bestLen = 0;
  labels.forEach((label, i) => {
    const words = normalise(label).split(' ').filter(Boolean);
    if (words.length && words.every((w) => said.has(w)) && words.length > bestLen) {
      best = i;
      bestLen = words.length;
    }
  });
  return best;
}

async function micAnswer() {
  const status = $('#q-status');
  status.textContent = t('q.listening');
  try {
    const heard = normalise(await listen(speechTag(), (text) => (status.textContent = text)));
    const opts = state._opts || [];
    let idx = matchOption(heard, opts.map((o) => o.label));
    const num = heard.match(/\d+/);
    if (idx < 0 && num) {
      const def = fields()[state._q.field];
      if (def.type === 'band') {
        const n = Number(num[0]);
        idx = def.options.findIndex((o) => o.band && n >= o.band.min && n <= o.band.max);
      }
    }
    if (idx < 0) {
      status.textContent = t('q.no_match');
      return;
    }
    answer(state._q.field, opts[idx].value);
  } catch (e) {
    status.textContent = t(e.message === 'denied' ? 'ask.denied' : e.message === 'unsupported' ? 'ask.unsupported' : 'q.no_match');
  }
}

// ---------- results ----------
function traceHtml(s, r) {
  const items = s.rules
    .map((rule) => {
      const tr = r.rules.find((x) => x.code === rule.code);
      if (!rule.hard) return '';
      const text = pick(rule.text);
      return `<li><span class="mark mark-${tr.outcome}" aria-hidden="true">${markIcon[tr.outcome]}</span>
        <span><span class="visually-hidden">${esc(t(`trace.${tr.outcome}`))}: </span><span${text.lang !== lang() ? ' lang="en"' : ''}>${esc(text.text)}</span>
        ${tr.outcome === 'fail' && rule.confidence === 'uncertain' ? `<br><span class="small muted">${esc(t('result.check_office'))}</span>` : ''}</span></li>`;
    })
    .join('');
  const priority = s.rules
    .filter((rule) => !rule.hard && r.rules.find((x) => x.code === rule.code)?.outcome === 'pass')
    .map((rule) => `<p class="small">${esc(t('result.priority', { rule: pick(rule.text).text }))}</p>`)
    .join('');
  return `<ul class="trace">${items}</ul>${priority}`;
}

function schemeCard(s, r) {
  const missing = r.result === 'NEED_MORE_INFO' ? r.missing_fields.filter((f) => !state.skipped.includes(f)) : [];
  return `<li class="card reveal">
    <div class="badges">${badge(r.result)}${trustChip(r.label)}</div>
    <h3${langAttr(s)}><span aria-hidden="true">${esc(s.icon)}</span> ${esc(schemeText(s, 'name'))}</h3>
    <p class="benefit"${langAttr(s)}>${esc(schemeText(s, 'summary'))}</p>
    ${r.stale ? `<p class="warn"><span aria-hidden="true">⚠️</span> ${esc(t(s.status === 'published' ? 'trust.stale_warning' : 'trust.draft_warning'))}</p>` : ''}
    <p class="small" style="margin:0;font-weight:700">${esc(t('result.because'))}</p>
    ${traceHtml(s, r)}
    ${missing.length ? `<p class="small" style="margin:0"><strong>${esc(t('result.missing'))}</strong> ${missing.map((f) => esc(fieldQuestion(f))).join(' · ')}</p>` : ''}
    <div class="card-actions">
      <a class="btn btn-primary" href="#/scheme/${esc(s.slug)}">${esc(t('result.details'))}</a>
      <button class="btn btn-outline" type="button" data-read-card><span aria-hidden="true">🔊</span> ${esc(t('result.read'))}</button>
      ${missing.length ? `<a class="btn btn-quiet" href="#/check">${esc(t('result.answer_more'))}</a>` : ''}
    </div></li>`;
}

function renderResults() {
  const answeredAny = Object.keys(state.profile).length || state.skipped.length;
  if (!answeredAny) {
    page(`<h1 class="h1" data-i18n="result.title"></h1><p class="hero-lead" style="margin-inline:0" data-i18n="result.empty"></p>
      <a class="btn btn-primary btn-hero" href="#/check" data-i18n="result.start"></a>`);
    return;
  }
  const m = runMatch();
  const groups = { LIKELY_ELIGIBLE: [], NEED_MORE_INFO: [], NOT_ELIGIBLE: [] };
  for (const r of m.results) groups[r.result].push(r);
  const byName = (a, b) => schemeText(schemeBy(a.scheme), 'name').localeCompare(schemeText(schemeBy(b.scheme), 'name'));
  const section = (key, cls) =>
    groups[key].length
      ? `<section class="results-group"><h2><span class="badge ${badgeClass[key]}" aria-hidden="true">${badgeIcon[key]}</span> ${esc(t(`result.group.${cls}`, { n: formatNumber(groups[key].length) }))}</h2>
         <ul class="cards">${groups[key].sort(byName).map((r) => schemeCard(schemeBy(r.scheme), r)).join('')}</ul></section>`
      : '';
  page(`<p class="kicker">${esc(t('nav.schemes'))}</p><h1 class="h1">${esc(t('result.title'))}</h1>
    <p class="hero-lead" style="margin-inline:0">${esc(t('result.lead'))}</p>
    <div class="hero-ctas" style="justify-content:flex-start">
      <button class="btn btn-outline" type="button" data-action="read-summary" data-summary="${esc(t('result.summary_read', { likely: formatNumber(groups.LIKELY_ELIGIBLE.length), need: formatNumber(groups.NEED_MORE_INFO.length) }))}"><span aria-hidden="true">🔊</span> ${esc(t('result.read'))}</button>
      ${m.next_question ? `<a class="btn btn-primary" href="#/check">${esc(t('result.answer_more'))}</a>` : ''}
      <button class="btn btn-quiet" type="button" data-action="restart">${esc(t('q.start_over'))}</button>
    </div>
    ${section('LIKELY_ELIGIBLE', 'likely')}${section('NEED_MORE_INFO', 'need_info')}${section('NOT_ELIGIBLE', 'not_eligible')}`);
}

// ---------- scheme detail ----------
function sourceCard(src, n) {
  let host = '';
  try {
    host = new URL(src.url).hostname;
  } catch {
    /* validated in CI */
  }
  const lines = [
    t(`doctype.${src.doc_type}`),
    src.published_date ? t('source.published', { date: formatDate(src.published_date) }) : '',
    src.fetched_at ? t('source.fetched', { date: formatDate(src.fetched_at) }) : '',
    src.verified_at ? t('source.verified', { date: formatDate(src.verified_at) }) : t('source.not_verified'),
  ].filter(Boolean);
  return `<li class="source-card" id="src-${esc(src.id)}"><span class="small muted">[${formatNumber(n)}] ${esc(src.publisher)}</span><br>
    <a href="${esc(src.url)}" rel="noopener noreferrer" target="_blank" lang="en">${esc(src.title)}</a> <span class="small muted">(${esc(host)})</span>
    <p class="meta">${lines.map(esc).join(' · ')}</p></li>`;
}

function renderScheme(route) {
  const s = schemeBy(route.arg);
  if (!s) {
    page(`<h1 class="h2">${esc(t('detail.not_found'))}</h1><a class="btn btn-outline" href="#/schemes">${esc(t('schemes.title'))}</a>`);
    return;
  }
  const r = runMatch().results.find((x) => x.scheme === s.slug);
  const answered = Object.keys(state.profile).length > 0;
  const srcIndex = new Map(s.sources.map((src, i) => [src.id, i + 1]));
  const cite = (id, locator) => {
    const src = s.sources.find((x) => x.id === id);
    return src ? `<a class="inline-cite" href="#/scheme/${esc(s.slug)}" data-cite="${esc(id)}">[${formatNumber(srcIndex.get(id))}] ${esc(src.publisher)}${locator ? ` · ${esc(t('source.locator', { locator }))}` : ''}</a>` : '';
  };
  const added = state.checklist.schemes.includes(s.slug);
  page(`<a class="btn btn-quiet" href="#/${answered ? 'results' : 'schemes'}">← ${esc(t(answered ? 'detail.back' : 'schemes.title'))}</a>
    <div class="detail-head">
      <span class="avatar" aria-hidden="true">${esc(s.icon)}</span>
      <p class="kicker">${esc(categoryLabel(s.category))}</p>
      <h1 class="h1"${langAttr(s)}>${esc(schemeText(s, 'name'))}</h1>
      <p class="muted">${esc(t('detail.ministry', { ministry: pick(s.ministry).text }))}</p>
      <div class="badges">${answered && r ? badge(r.result) : ''}${trustChip(r.label)}</div>
      ${s.status !== 'published' ? `<p class="warn"><span aria-hidden="true">⚠️</span> ${esc(t('trust.draft_warning'))}</p>` : r.stale ? `<p class="warn"><span aria-hidden="true">⚠️</span> ${esc(t('trust.stale_warning'))}</p>` : ''}
      ${inEnglish(s) ? `<p class="small muted">${esc(t('detail.in_english'))}</p>` : ''}
      <div class="card-actions">
        <button class="btn btn-outline" type="button" data-action="read-scheme"><span aria-hidden="true">🔊</span> ${esc(t('detail.read'))}</button>
        <a class="btn btn-primary" href="${esc(s.official_apply_url)}" rel="noopener noreferrer" target="_blank">${esc(t('detail.apply'))} ↗</a>
      </div>
    </div>
    <div class="detail-grid">
      <div class="detail-main" id="scheme-read">
        <section class="panel"${langAttr(s)}><p class="lead">${esc(schemeText(s, 'summary'))}</p>
          <h2>${esc(t('detail.benefit'))}</h2><p>${esc(schemeText(s, 'benefit'))}</p>
          <h2>${esc(t('detail.how'))}</h2><p>${esc(schemeText(s, 'how_to_apply'))}</p></section>
        <section class="panel" style="margin-top:16px"><h2>${esc(t('detail.rules'))}</h2>
          <ul class="trace">${s.rules
            .filter((rule) => rule.hard)
            .map((rule) => {
              const tr = r.rules.find((x) => x.code === rule.code);
              const text = pick(rule.text);
              const mark = answered ? `<span class="mark mark-${tr.outcome}" aria-hidden="true">${markIcon[tr.outcome]}</span>` : '<span class="mark mark-fail" aria-hidden="true">•</span>';
              return `<li>${mark}<span>${answered ? `<span class="visually-hidden">${esc(t(`trace.${tr.outcome}`))}: </span>` : ''}<span${text.lang !== lang() ? ' lang="en"' : ''}>${esc(text.text)}</span><br>
                ${trustChip(rule.confidence)} ${cite(rule.source, rule.locator)}</span></li>`;
            })
            .join('')}</ul></section>
      </div>
      <aside>
        <section class="panel"><h2>${esc(t('detail.documents'))}</h2>
          <ul class="doc-list">${s.documents
            .map((d) => {
              const doc = state.bundle.documents.documents[d.code];
              return `<li><span aria-hidden="true">${esc(doc?.icon || '📄')}</span><span>${esc(pick(doc?.name).text)}${d.mandatory ? '' : ` <span class="small muted">(${esc(t('detail.optional'))})</span>`}</span></li>`;
            })
            .join('')}</ul>
          <button class="btn ${added ? 'btn-outline' : 'btn-primary'} long" type="button" data-action="add-checklist" style="margin-top:16px" ${added ? 'disabled' : ''}>${esc(t(added ? 'detail.added' : 'detail.add_checklist'))}</button>
        </section>
        <section class="panel" style="margin-top:16px"><h2>${esc(t('detail.sources'))}</h2>
          <ol class="source-list">${s.sources.map((src, i) => sourceCard(src, i + 1)).join('')}</ol>
          <p style="margin-top:16px"><a class="btn btn-quiet" rel="noopener" target="_blank" href="${esc(`${REPORT_URL}?title=${encodeURIComponent(`Outdated info: ${s.slug}`)}&labels=outdated-info`)}">${esc(t('detail.report'))}</a></p>
        </section>
      </aside>
    </div>`);
  state._scheme = s;
}

function schemeSpeech(s) {
  const verified = s.sources.filter((x) => x.fetched_at);
  const srcLine = verified[0] ? t('source.read', { publisher: verified[0].publisher, date: formatDate(verified[0].verified_at || verified[0].fetched_at) }) : '';
  return [schemeText(s, 'name'), schemeText(s, 'summary'), t('detail.benefit'), schemeText(s, 'benefit'), t('detail.how'), schemeText(s, 'how_to_apply'), s.status !== 'published' ? t('trust.draft_warning') : '', srcLine]
    .filter(Boolean)
    .join('. ');
}

// ---------- schemes list ----------
function renderSchemes(route) {
  const cat = route.arg;
  const list = state.bundle.schemes.filter((s) => !cat || s.category === cat);
  const cats = [...new Set(state.bundle.schemes.map((s) => s.category))];
  page(`<p class="kicker">${esc(t('nav.schemes'))}</p><h1 class="h1">${esc(cat ? categoryLabel(cat) : t('schemes.title'))}</h1>
    <p class="hero-lead" style="margin-inline:0">${esc(t('schemes.lead'))}</p>
    <ul class="tiles" style="margin-bottom:32px">
      <li><a class="tile ${!cat ? 'selected' : ''}" href="#/schemes" ${!cat ? 'aria-current="page"' : ''}><span class="tile-icon" aria-hidden="true">📋</span><span>${esc(t('schemes.all'))}</span></a></li>
      ${cats.map((c) => `<li><a class="tile ${c === cat ? 'selected' : ''}" href="#/schemes/${esc(c)}" ${c === cat ? 'aria-current="page"' : ''}><span class="tile-icon" aria-hidden="true">${CATEGORY_ICONS[c] || '📌'}</span><span>${esc(categoryLabel(c))}</span></a></li>`).join('')}
    </ul>
    <ul class="cards">${list.map(listCard).join('')}</ul>`);
}

function listCard(s) {
  const r = runMatch().results.find((x) => x.scheme === s.slug);
  const answered = Object.keys(state.profile).length > 0;
  return `<li class="card reveal">
    <div class="badges">${answered ? badge(r.result) : ''}${trustChip(r.label)}${s.status !== 'published' ? `<span class="chip chip-uncertain">${esc(t('preview.badge'))}</span>` : ''}</div>
    <h3${langAttr(s)}><span aria-hidden="true">${esc(s.icon)}</span> ${esc(schemeText(s, 'name'))}</h3>
    <p class="benefit"${langAttr(s)}>${esc(schemeText(s, 'summary'))}</p>
    <div class="card-actions"><a class="btn btn-primary" href="#/scheme/${esc(s.slug)}">${esc(t('result.details'))}</a></div></li>`;
}

// ---------- checklist ----------
function renderChecklist() {
  const slugs = state.checklist.schemes.filter((slug) => schemeBy(slug));
  if (!slugs.length) {
    page(`<h1 class="h1" data-i18n="checklist.title"></h1><p class="hero-lead" style="margin-inline:0" data-i18n="checklist.empty"></p>
      <a class="btn btn-primary" href="#/schemes" data-i18n="schemes.title"></a>`);
    return;
  }
  const docs = new Map();
  for (const slug of slugs) {
    const s = schemeBy(slug);
    for (const d of s.documents) {
      const entry = docs.get(d.code) || { code: d.code, mandatory: false, schemes: [] };
      entry.mandatory ||= d.mandatory;
      entry.schemes.push(schemeText(s, 'name'));
      docs.set(d.code, entry);
    }
  }
  const items = [...docs.values()].sort((a, b) => Number(b.mandatory) - Number(a.mandatory));
  const have = items.filter((d) => state.checklist.have[d.code] === true).length;
  page(`<p class="kicker">${esc(t('nav.checklist'))}</p><h1 class="h1">${esc(t('checklist.title'))}</h1>
    <p class="hero-lead" style="margin-inline:0">${esc(t('checklist.lead'))}</p>
    <p><strong>${esc(t('checklist.progress', { have: formatNumber(have), total: formatNumber(items.length) }))}</strong></p>
    <div class="progress"><span style="width:${Math.round((have / items.length) * 100)}%"></span></div>
    <ul class="cards">${items
      .map((d) => {
        const doc = state.bundle.documents.documents[d.code] || {};
        const v = state.checklist.have[d.code];
        const name = pick(doc.name).text;
        return `<li class="check-item">
        <h3><span aria-hidden="true">${esc(doc.icon || '📄')}</span> ${esc(name)} ${d.mandatory ? '' : `<span class="small muted">(${esc(t('detail.optional'))})</span>`}</h3>
        <p class="small muted" style="margin:0">${esc(t('checklist.needed_for', { schemes: d.schemes.join(', ') }))}</p>
        <div class="seg" role="group" aria-label="${esc(name)}">
          <button type="button" data-have="${esc(d.code)}" data-v="1" aria-pressed="${v === true}">${esc(t('checklist.have'))}</button>
          <button type="button" data-have="${esc(d.code)}" data-v="0" aria-pressed="${v === false}">${esc(t('checklist.dont_have'))}</button>
        </div>
        <details ${v === false ? 'open' : ''}><summary>${esc(t('checklist.how_to'))}</summary>
          <p>${esc(pick(doc.how_to).text)}</p>
          ${doc.office ? `<p class="small">${esc(t('checklist.office', { office: pick(doc.office).text }))}</p>` : ''}
          ${doc.portal_url ? `<p><a href="${esc(doc.portal_url)}" rel="noopener noreferrer" target="_blank">${esc(t('checklist.portal'))} ↗</a></p>` : ''}
          ${doc.source && !doc.source.verified_at ? `<p class="small muted">${esc(t('source.not_verified'))}</p>` : ''}
        </details></li>`;
      })
      .join('')}</ul>
    <p style="margin-top:24px"><button class="btn btn-outline" type="button" data-action="clear-checklist">${esc(t('checklist.clear'))}</button></p>`);
}

// ---------- search ----------
export function searchSchemes(query) {
  const words = normalise(query).split(' ').filter((w) => w.length > 1);
  if (!words.length) return [];
  return state.bundle.schemes
    .map((s) => {
      const hay = normalise(
        [
          ...['en', lang()].flatMap((l) => (s.content[l] ? Object.values(s.content[l]) : [])),
          pick(s.ministry).text,
          categoryLabel(s.category),
          s.slug,
        ].join(' '),
      );
      const score = words.reduce((n, w) => n + (hay.includes(w) ? 1 : 0), 0);
      return { s, score };
    })
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .map((x) => x.s);
}

function searchResultsHtml(query) {
  if (!query) return '';
  const found = searchSchemes(query);
  if (!found.length) return `<p class="warn"><span aria-hidden="true">🔎</span> ${esc(t('search.none'))}</p>`;
  return `<p role="status">${esc(t('search.results', { n: formatNumber(found.length) }))}</p><ul class="cards">${found.map(listCard).join('')}</ul>`;
}

function renderSearch(route) {
  const q = route.query.get('q') || '';
  page(`<h1 class="h1">${esc(t('search.title'))}</h1><p class="muted">${esc(t('search.note'))}</p>
    <form class="search-form" role="search" data-form="search">
      <label class="visually-hidden" for="search-input">${esc(t('search.label'))}</label>
      <input id="search-input" type="search" value="${esc(q)}" placeholder="${esc(t('search.placeholder'))}" autocomplete="off">
      <button class="btn btn-primary" type="submit">${esc(t('search.button'))}</button>
    </form>${searchResultsHtml(q)}`);
}

// ---------- help, privacy ----------
function renderHelp() {
  page(`<h1 class="h1">${esc(t('help.title'))}</h1><p class="hero-lead" style="margin-inline:0">${esc(t('help.body'))}</p>`);
}

function renderPrivacy() {
  page(`<h1 class="h1">${esc(t('privacy.title'))}</h1>
    <p>${esc(t('privacy.body1'))}</p><p>${esc(t('privacy.body2'))}</p><p>${esc(t('privacy.body3'))}</p>
    <button class="btn btn-primary" type="button" data-action="delete-data">${esc(t('privacy.delete'))}</button>`);
}

// ---------- ask sheet ----------
function openAsk() {
  const sheet = $('#ask-sheet');
  $('#ask-status').textContent = canListen() ? '' : t('ask.unsupported');
  $('#ask-mic').hidden = !canListen();
  $('#ask-results').innerHTML = '';
  if (sheet.showModal) sheet.showModal();
  else sheet.setAttribute('open', '');
  (canListen() ? $('#ask-mic') : $('#ask-input')).focus();
}
function closeAsk() {
  const sheet = $('#ask-sheet');
  if (sheet.close) sheet.close();
  else sheet.removeAttribute('open');
}
async function askListen() {
  const mic = $('#ask-mic');
  const status = $('#ask-status');
  mic.classList.add('listening');
  status.textContent = t('ask.listening');
  try {
    const text = await listen(speechTag(), (partial) => {
      $('#ask-input').value = partial;
    });
    if (text) {
      $('#ask-input').value = text;
      status.textContent = t('ask.heard', { text });
      $('#ask-results').innerHTML = searchResultsHtml(text);
    } else status.textContent = '';
  } catch (e) {
    status.textContent = t(e.message === 'denied' ? 'ask.denied' : e.message === 'unsupported' ? 'ask.unsupported' : 'q.no_match');
  } finally {
    mic.classList.remove('listening');
  }
}

// ---------- chrome: nav, language, motion ----------
function closeMenu() {
  $('#nav-links').classList.remove('open');
  $('#menu-btn').setAttribute('aria-expanded', 'false');
  $('#menu-btn').setAttribute('aria-label', t('nav.menu'));
}

function buildLanguageControls() {
  $('#lang-select').innerHTML = LANGS.map((l) => `<option value="${l.code}" lang="${l.code}">${esc(l.name)}</option>`).join('');
  $('#footer-langs').innerHTML = LANGS.map((l) => `<li><button type="button" data-lang="${l.code}" lang="${l.code}">${esc(l.name)}</button></li>`).join('');
}

function setMotion(reduced) {
  document.documentElement.classList.toggle('reduce-motion', reduced);
  $('#reduce-motion').checked = reduced;
  store.set(KEYS.motion, reduced ? '1' : '0');
}

let revealObserver;
function observeReveals() {
  const els = $$('.reveal:not(.in)');
  if (!('IntersectionObserver' in window)) return els.forEach((el) => el.classList.add('in'));
  revealObserver ||= new IntersectionObserver(
    (entries) => entries.forEach((e) => e.isIntersecting && (e.target.classList.add('in'), revealObserver.unobserve(e.target))),
    { rootMargin: '0px 0px -40px' },
  );
  els.forEach((el) => revealObserver.observe(el));
}

function preloadHeroImage() {
  const img = new Image();
  img.onload = () => $('.hero-word').classList.add('has-image');
  img.src = 'img/collage.svg';
}

// ---------- events ----------
function wire() {
  document.addEventListener('click', (e) => {
    const el = e.target.closest('button, a');
    if (!el) return;
    const action = el.dataset.action;
    if (el.dataset.answer !== undefined && state._opts) {
      const o = state._opts[Number(el.dataset.answer)];
      if (state.voiceMode) say(o.label);
      answer(state._q.field, o.value);
    } else if (action === 'back') goBack();
    else if (action === 'skip') skipCurrent();
    else if (action === 'speak-q') {
      state.voiceMode = true;
      speakQuestion();
    } else if (action === 'mic-q') {
      state.voiceMode = true;
      micAnswer();
    } else if (action === 'ask') openAsk();
    else if (action === 'close-ask') closeAsk();
    else if (action === 'read-summary') say(el.dataset.summary);
    else if (el.hasAttribute('data-read-card')) say(readableText(el.closest('.card')));
    else if (action === 'read-scheme' && state._scheme) say(schemeSpeech(state._scheme));
    else if (action === 'restart') {
      state.profile = {};
      state.skipped = [];
      state.history = [];
      saveProfile();
      location.hash = '#/check';
    } else if (action === 'add-checklist' && state._scheme) {
      if (!state.checklist.schemes.includes(state._scheme.slug)) state.checklist.schemes.push(state._scheme.slug);
      saveChecklist();
      el.textContent = t('detail.added');
      el.disabled = true;
      notice(t('detail.added'), 3000);
    } else if (el.dataset.have) {
      state.checklist.have[el.dataset.have] = el.dataset.v === '1';
      saveChecklist();
      renderChecklist();
    } else if (action === 'clear-checklist') {
      state.checklist = { schemes: [], have: {} };
      saveChecklist();
      renderChecklist();
    } else if (action === 'delete-data') {
      if (confirm(t('privacy.confirm'))) {
        deleteAllData();
        notice(t('privacy.deleted'));
      }
    } else if (el.dataset.lang) {
      changeLang(el.dataset.lang);
    } else if (el.dataset.rail) {
      const rail = $('#rail');
      rail.scrollBy({ left: Number(el.dataset.rail) * rail.clientWidth * 0.8, behavior: document.documentElement.classList.contains('reduce-motion') ? 'auto' : 'smooth' });
    } else if (el.dataset.cite) {
      e.preventDefault();
      $(`#src-${CSS.escape(el.dataset.cite)}`)?.scrollIntoView({ block: 'center' });
    }
  });

  document.addEventListener('submit', (e) => {
    const form = e.target;
    e.preventDefault();
    if (form.dataset.form === 'exact') {
      const input = $('#exact-input');
      const n = Number(input.value);
      if (input.value === '' || !Number.isFinite(n)) {
        $('#q-status').textContent = t('q.exact_invalid');
        return;
      }
      answer(state._q.field, n, { exact: true });
    } else if (form.dataset.form === 'search') {
      location.hash = `#/search?q=${encodeURIComponent($('#search-input').value)}`;
    } else if (form.id === 'ask-form') {
      $('#ask-results').innerHTML = searchResultsHtml($('#ask-input').value);
    }
  });

  $('#lang-select').addEventListener('change', (e) => changeLang(e.target.value));
  $('#menu-btn').addEventListener('click', () => {
    const open = !$('#nav-links').classList.contains('open');
    $('#nav-links').classList.toggle('open', open);
    $('#menu-btn').setAttribute('aria-expanded', String(open));
    $('#menu-btn').setAttribute('aria-label', t(open ? 'nav.close' : 'nav.menu'));
  });
  $('#listen').addEventListener('click', () => {
    if ($('#listen').getAttribute('aria-pressed') === 'true') return stop();
    state.voiceMode = true;
    const root = $('#home').hidden ? $('#view') : $('#home');
    say(readableText(root));
  });
  onSpeakingChange((on) => {
    $('#listen').setAttribute('aria-pressed', String(on));
    $('#listen [data-i18n]').textContent = t(on ? 'nav.stop' : 'nav.listen');
  });
  $('#ask-mic').addEventListener('click', askListen);
  $('#reduce-motion').addEventListener('change', (e) => setMotion(e.target.checked));
  window.addEventListener('hashchange', render);
  window.addEventListener('scroll', () => $('#nav').classList.toggle('scrolled', window.scrollY > 8), { passive: true });
  document.addEventListener('keydown', (e) => e.key === 'Escape' && closeMenu());
}

async function changeLang(code) {
  stop();
  await setLang(code);
  $('#lang-select').value = code;
  render();
}

// ---------- start ----------
async function start() {
  // Entrance motion only if the script arrives quickly; on a slow network the content
  // has already been read, and re-animating it would just make it jump.
  if (performance.now() < 1200) document.body.classList.add('anim');
  loadState();
  buildLanguageControls();
  setMotion(store.get(KEYS.motion) === '1');
  wire();
  const code = detectLang();
  try {
    await Promise.all([setLang(code), loadBundle()]);
  } catch (err) {
    notice(t('common.error'), 15000);
    console.error(err);
    return;
  }
  $('#lang-select').value = lang();
  preloadHeroImage();
  render();
  if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('sw.js').catch(() => {});
  window.addEventListener('offline', () => notice(t('common.offline')));
}

start();
