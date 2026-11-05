// Builds the static data the PWA uses offline (shape: docs/RULES_ENGINE.md §9.7, split by state).
//   frontend/public/data/bundle.json            fields, documents, published central schemes, and an index of states
//   frontend/public/data/states/<code>.json     published schemes of one state (loaded once the user picks a state)
//   --include-drafts writes the same files with .preview.json (every non-archived scheme)
//   --check fails instead of writing when a file is out of date (for CI)
// Files are rewritten only when their content changes, so generated_at stays stable.
import { readFileSync, readdirSync, writeFileSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { citedSources } from './lib/quality.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const args = new Set(process.argv.slice(2));
const includeDrafts = args.has('--include-drafts');
const check = args.has('--check');
const suffix = includeDrafts ? '.preview.json' : '.json';
const readJson = (p) => JSON.parse(readFileSync(join(root, p), 'utf8'));
const sha = (v) => createHash('sha256').update(JSON.stringify(v)).digest('hex');

const fields = readJson('content/fields.json');
const documents = readJson('content/documents.json');
const all = readdirSync(join(root, 'content/schemes'))
  .filter((f) => f.endsWith('.json'))
  .sort()
  .map((f) => readJson(`content/schemes/${f}`))
  .filter((s) => s.status !== 'archived' && (includeDrafts || s.status === 'published'));

// What the daily re-check found (docs/KNOWLEDGE_BASE.md §5). Scheme files are not touched, because
// any edit to one voids its reviews; the warning travels on the source in the bundle instead.
const sourceStatus = existsSync(join(root, 'content/source-status.json')) ? readJson('content/source-status.json').sources : {};
// A source nothing cites is only a link for the reader: its change can't make a fact out of date.
for (const s of all) {
  const cited = citedSources(s);
  for (const src of s.sources) {
    const st = sourceStatus[`${s.slug}/${src.id}`];
    if (st?.status === 'changed' && cited.has(src.id)) src.changed_at = st.since;
  }
}

const central = all.filter((s) => s.level !== 'state');
const byState = {};
for (const s of all.filter((x) => x.level === 'state')) (byState[s.state_code] ||= []).push(s);

let stale = 0;
function emit(rel, body) {
  const version = sha(body);
  const file = join(root, 'frontend/public/data', rel);
  let previous = null;
  if (existsSync(file)) {
    try {
      previous = JSON.parse(readFileSync(file, 'utf8'));
    } catch {
      previous = null;
    }
  }
  if (previous?.version === version) return version;
  if (check) {
    console.error(`frontend/public/data/${rel} is out of date: run npm run build`);
    stale++;
    return version;
  }
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify({ version, generated_at: new Date().toISOString().slice(0, 10), ...body }) + '\n');
  console.log(`wrote frontend/public/data/${rel}`);
  return version;
}

const states = {};
for (const [code, schemes] of Object.entries(byState)) {
  states[code] = { count: schemes.length, version: emit(`states/${code}${suffix}`, { include_drafts: includeDrafts, state: code, schemes }) };
}
// Remove files of states that no longer have schemes.
const stateDir = join(root, 'frontend/public/data/states');
if (existsSync(stateDir) && !check) {
  for (const f of readdirSync(stateDir)) {
    const code = f.split('.')[0];
    if (f.endsWith(suffix) && (includeDrafts || !f.endsWith('.preview.json')) && !states[code]) rmSync(join(stateDir, f));
  }
}
// Machine-translated scheme text (content/translations): served as it is, one file per language.
const trDir = join(root, 'content/translations');
const translated = [];
const english = (t) => (t && typeof t === 'object' ? t.en : t);
const fresh = (key, text, tr) => text && tr.source_hash?.[key] === `sha256:${createHash('sha256').update(text, 'utf8').digest('hex')}`;
function currentTranslations(tr) {
  const schemes = {};
  for (const s of all) {
    const from = tr.schemes?.[s.slug];
    if (!from) continue;
    const out = {};
    for (const k of ['name', 'summary', 'benefit', 'how_to_apply']) if (from[k] && fresh(`schemes.${s.slug}.${k}`, s.content.en[k], tr)) out[k] = from[k];
    const rules = {};
    for (const r of s.rules) if (from.rules?.[r.code] && fresh(`schemes.${s.slug}.rules.${r.code}`, english(r.text), tr)) rules[r.code] = from.rules[r.code];
    if (Object.keys(rules).length) out.rules = rules;
    if (Object.keys(out).length) schemes[s.slug] = out;
  }
  const docs = {};
  for (const [code, d] of Object.entries(documents.documents)) {
    const from = tr.documents?.[code];
    if (!from) continue;
    const out = {};
    for (const k of ['name', 'how_to', 'office']) if (from[k] && fresh(`documents.${code}.${k}`, english(d[k]), tr)) out[k] = from[k];
    if (Object.keys(out).length) docs[code] = out;
  }
  return { schemes, documents: docs };
}
if (existsSync(trDir)) {
  for (const f of readdirSync(trDir).filter((x) => x.endsWith('.json'))) {
    const tr = readJson(`content/translations/${f}`);
    const served = currentTranslations(tr);
    if (Object.keys(served.schemes).length) {
      translated.push(f.replace('.json', ''));
      if (!includeDrafts) emit(`translations/${f}`, served);
    }
  }
}
emit(`bundle${suffix}`, { include_drafts: includeDrafts, fields, documents, states, translations: translated, schemes: central });
console.log(`${includeDrafts ? 'preview' : 'published'}: ${central.length} central schemes, ${Object.keys(states).length} states with ${all.length - central.length} state schemes`);
if (stale) process.exit(1);
