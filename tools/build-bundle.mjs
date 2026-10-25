// Builds the static data the PWA uses offline (shape: docs/RULES_ENGINE.md §9.7, split by state).
//   frontend/data/bundle.json            fields, documents, published central schemes, and an index of states
//   frontend/data/states/<code>.json     published schemes of one state (loaded once the user picks a state)
//   --include-drafts writes the same files with .preview.json (every non-archived scheme)
//   --check fails instead of writing when a file is out of date (for CI)
// Files are rewritten only when their content changes, so generated_at stays stable.
import { readFileSync, readdirSync, writeFileSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

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

const central = all.filter((s) => s.level !== 'state');
const byState = {};
for (const s of all.filter((x) => x.level === 'state')) (byState[s.state_code] ||= []).push(s);

let stale = 0;
function emit(rel, body) {
  const version = sha(body);
  const file = join(root, 'frontend/data', rel);
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
    console.error(`frontend/data/${rel} is out of date: run npm run build`);
    stale++;
    return version;
  }
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify({ version, generated_at: new Date().toISOString().slice(0, 10), ...body }) + '\n');
  console.log(`wrote frontend/data/${rel}`);
  return version;
}

const states = {};
for (const [code, schemes] of Object.entries(byState)) {
  states[code] = { count: schemes.length, version: emit(`states/${code}${suffix}`, { include_drafts: includeDrafts, state: code, schemes }) };
}
// Remove files of states that no longer have schemes.
const stateDir = join(root, 'frontend/data/states');
if (existsSync(stateDir) && !check) {
  for (const f of readdirSync(stateDir)) {
    const code = f.split('.')[0];
    if (f.endsWith(suffix) && (includeDrafts || !f.endsWith('.preview.json')) && !states[code]) rmSync(join(stateDir, f));
  }
}
emit(`bundle${suffix}`, { include_drafts: includeDrafts, fields, documents, states, schemes: central });
console.log(`${includeDrafts ? 'preview' : 'published'}: ${central.length} central schemes, ${Object.keys(states).length} states with ${all.length - central.length} state schemes`);
if (stale) process.exit(1);
