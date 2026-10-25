// Copy lint: blocks phrases that promise eligibility or approval (CLAUDE.md principle 1).
// Checks every string in frontend/public/locales/*.json and content/**/*.json, and the assistant prompts in backend/src/main/resources/prompts/.
// Usage: node tools/lint-copy.mjs   (exit 1 on any hit)
//
// The non-English lists were machine-authored and must be extended by native-speaker reviewers
// (docs/I18N_AND_VOICE.md §6). ALLOW strips official names before matching, e.g. the
// "Viksit Bharat – Guarantee for Rozgar and Ajeevika Mission (Gramin) Act" and its job card.
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

// The lists live in banned-phrases.json, shared with the API's answer validator.
const PHRASES = JSON.parse(readFileSync(join(root, 'tools/banned-phrases.json'), 'utf8'));
export const BANNED = Object.fromEntries(Object.entries(PHRASES.banned).map(([l, list]) => [l, list.map((p) => new RegExp(p.pattern, p.flags))]));
const ALLOW = PHRASES.allow.map((p) => new RegExp(p.pattern, `g${p.flags}`));

const LOCALE_OF = (file) => file.match(/locales[\\/](\w+)\.json$/)?.[1];

function strings(value, path, out) {
  if (typeof value === 'string') out.push([path, value]);
  else if (Array.isArray(value)) value.forEach((v, i) => strings(v, `${path}[${i}]`, out));
  else if (value && typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) if (!k.startsWith('$')) strings(v, path ? `${path}.${k}` : k, out);
  }
  return out;
}

function walk(dir, ext, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, ext, out);
    else if (ext.some((e) => name.endsWith(e))) out.push(p);
  }
  return out;
}

export function lintText(text, langs = Object.keys(BANNED)) {
  let t = text.normalize('NFC').replace(/[‌‍]/g, '');
  for (const a of ALLOW) t = t.replace(a, ' ');
  const hits = [];
  for (const l of langs) for (const re of BANNED[l]) if (re.test(t)) hits.push(re.source);
  return hits;
}

function main() {
  const files = [
    ...walk(join(root, 'frontend/public/locales'), ['.json']),
    ...walk(join(root, 'content'), ['.json']),
    ...walk(join(root, 'backend/src/main/resources/prompts'), ['.txt', '.md']),
  ];
  const problems = [];
  for (const file of files) {
    const rel = relative(root, file);
    if (rel.endsWith('review-status.json')) continue;
    const items = file.endsWith('.json') ? strings(JSON.parse(readFileSync(file, 'utf8')), '', []) : [['', readFileSync(file, 'utf8')]];
    const loc = LOCALE_OF(file);
    // Locale files: English plus their own language. Content and prompts: every language.
    const langs = loc ? [...new Set(['en', loc])].filter((l) => BANNED[l]) : Object.keys(BANNED);
    for (const [path, text] of items) {
      for (const hit of lintText(text, langs)) problems.push(`${rel} ${path}: banned phrase /${hit}/ in "${text.slice(0, 90)}"`);
    }
  }
  if (problems.length) {
    console.error(problems.join('\n'));
    console.error(`\n${problems.length} banned phrase(s). Use "may be eligible", "likely relevant" or "need more information".`);
    process.exit(1);
  }
  console.log(`copy lint OK: ${files.length} files`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
