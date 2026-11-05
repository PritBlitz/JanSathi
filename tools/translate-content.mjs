// Machine translation of scheme and document text into content/translations/<lang>.json, through
// the ML service's /v1/translate (ml/README.md). These files are separate from the reviewed scheme
// files: changing them never changes a rule, and the app labels the text as machine translated.
//
//   node tools/translate-content.mjs [--lang bn,ta] [--url http://localhost:8090] [--all] [--check] [--drop-stale]
//
// Only published schemes and the document list are sent, unless --all adds drafts and schemes
// waiting for review. Rerun it after publishing: it fills in just what is new.
// Only fields that are new, changed or still missing are sent. Each field records the hash of its
// English source in source_hash, so changed English invalidates exactly that field. Stale fields
// are removed even if the service is down or refuses them: English is better than an old fact.
//
// A field is written only if it passes every check here, on top of the service's own: same numbers
// as the English, same URLs and acronyms, no banned phrase. Otherwise it stays out and the app
// shows English. --check changes nothing and exits 1 if anything is stale, missing or unsafe.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { lintText } from './lint-copy.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = join(root, 'content/translations');
export const LANGS = ['bn', 'ta', 'te', 'mr', 'gu', 'kn', 'ml', 'pa'];
const SCHEME_FIELDS = ['name', 'summary', 'benefit', 'how_to_apply'];
const DOCUMENT_FIELDS = ['name', 'how_to', 'office'];
const BATCH = 4; // small enough that one request answers inside fetch's 5-minute header timeout on a CPU

export const hashOf = (text) => `sha256:${createHash('sha256').update(text, 'utf8').digest('hex')}`;

/** Every English text to translate, keyed by its path in the output file. */
export function sources(schemes, documents) {
  const out = new Map();
  for (const s of schemes) {
    for (const f of SCHEME_FIELDS) if (s.content?.en?.[f]) out.set(`schemes.${s.slug}.${f}`, s.content.en[f]);
    for (const r of s.rules ?? []) if (r.text?.en) out.set(`schemes.${s.slug}.rules.${r.code}`, r.text.en);
  }
  for (const [code, d] of Object.entries(documents)) {
    for (const f of DOCUMENT_FIELDS) if (d[f]?.en) out.set(`documents.${code}.${f}`, d[f].en);
  }
  return out;
}

function get(file, key) {
  const [kind, id, field, code] = key.split('.');
  const entry = file[kind]?.[id];
  return field === 'rules' ? entry?.rules?.[code] : entry?.[field];
}

function put(file, key, text) {
  const [kind, id, field, code] = key.split('.');
  const entry = (file[kind][id] ??= {});
  if (field === 'rules') (entry.rules ??= {})[code] = text;
  else entry[field] = text;
}

/** Numbers, ignoring grouping commas and script; URLs are compared as terms instead. */
const URL = /https?:\/\/\S+|www\.\S+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:gov\.in|nic\.in|org\.in|co\.in|in|org|com)\b(?:\/\S*)?/gi;
const asciiDigits = (t) => t.replace(/\p{Nd}/gu, (c) => {
  const code = c.codePointAt(0);
  for (let zero = code; zero > code - 10; zero--) if (!/\p{Nd}/u.test(String.fromCodePoint(zero - 1))) return String(code - zero);
  return c;
});
export const numbersOf = (text) => (asciiDigits(text.replace(URL, ' ')).match(/\d[\d,]*(?:\.\d+)?/g) ?? [])
  .map((n) => n.replace(/,/g, '').replace(/\.$/, '')).sort();

const ACRONYM = /\b(?:[a-z]-)?[A-Z][A-Z0-9]+(?:-[A-Z0-9]+)*\b/g;
export const termsOf = (text) => [
  ...(text.match(URL) ?? []).map((u) => u.replace(/[.,;)]+$/, '')),
  ...(text.replace(URL, ' ').match(ACRONYM) ?? []),
];

/** Why this translation cannot ship, or null. */
export function problem(english, text, lang) {
  if (String(numbersOf(english)) !== String(numbersOf(text))) return 'numbers differ';
  const lost = termsOf(english).find((t) => !text.includes(t));
  if (lost) return `lost ${lost}`;
  const banned = lintText(text, ['en', lang]);
  return banned.length ? `banned phrase /${banned[0]}/` : null;
}

function load(lang) {
  const path = join(OUT, `${lang}.json`);
  return existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : { schemes: {}, documents: {}, source_hash: {} };
}

const sorted = (value) => (value && typeof value === 'object' && !Array.isArray(value)
  ? Object.fromEntries(Object.keys(value).sort().map((k) => [k, sorted(value[k])]))
  : value);

function save(lang, file) {
  mkdirSync(OUT, { recursive: true });
  const path = join(OUT, `${lang}.json`);
  writeFileSync(`${path}.part`, `${JSON.stringify(sorted(file), null, 1)}\n`);
  renameSync(`${path}.part`, path);
}

/** Splits fields into current ones (kept) and the rest (to translate). Removes what is stale. */
export function plan(file, wanted, lang) {
  const fresh = { model: file.model, schemes: {}, documents: {}, source_hash: {} };
  const todo = [];
  const unsafe = [];
  for (const [key, english] of wanted) {
    const hash = hashOf(english);
    const text = get(file, key);
    const why = text && file.source_hash?.[key] === hash ? problem(english, text, lang) : 'missing';
    if (why === null) {
      put(fresh, key, text);
      fresh.source_hash[key] = hash;
    } else {
      if (why !== 'missing') unsafe.push(`${key}: ${why}`);
      todo.push(key);
    }
  }
  return { fresh, todo, unsafe };
}

async function translate(url, token, texts, target) {
  const reply = await fetch(`${url}/v1/translate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ texts, source: 'en', target }),
    signal: AbortSignal.timeout(3_600_000),
  });
  if (!reply.ok) throw new Error(`/v1/translate answered ${reply.status}: ${await reply.text()}`);
  return reply.json();
}

function content() {
  const dir = join(root, 'content/schemes');
  const schemes = readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => JSON.parse(readFileSync(join(dir, f), 'utf8')));
  const unpublished = new Set(schemes.filter((s) => s.status !== 'published').map((s) => s.slug));
  return { wanted: sources(schemes, JSON.parse(readFileSync(join(root, 'content/documents.json'), 'utf8')).documents), unpublished };
}

/** Drafts wait, unless asked for: their text may still change in review. */
export const dueNow = (key, unpublished) => !(key.startsWith('schemes.') && unpublished.has(key.split('.')[1]));

async function main(args) {
  const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
  const langs = option('--lang')?.split(',') ?? LANGS;
  const url = (option('--url') ?? process.env.ML_URL ?? 'http://localhost:8090').replace(/\/$/, '');
  const token = process.env.ML_SERVICE_TOKEN;
  const check = args.includes('--check');
  const { wanted, unpublished } = content();
  const all = args.includes('--all');
  let bad = 0;
  for (const lang of langs) {
    const { fresh, todo: outstanding, unsafe } = plan(load(lang), wanted, lang);
    const todo = all ? outstanding : outstanding.filter((k) => dueNow(k, unpublished));
    if (check) {
      console.log(`${lang}: ${wanted.size - outstanding.length}/${wanted.size} current, ${todo.length} due now, ${outstanding.length - todo.length} waiting for review`);
      unsafe.forEach((u) => console.log(`  ${u}`));
      bad += unsafe.length;
      continue;
    }
    save(lang, fresh); // stale text goes first, whatever happens next
    if (args.includes('--drop-stale')) continue;
    const started = Date.now();
    let done = 0;
    const refused = [];
    for (let i = 0; i < todo.length; i += BATCH) {
      const keys = todo.slice(i, i + BATCH);
      const reply = await translate(url, token, keys.map((k) => wanted.get(k)), lang);
      fresh.model = reply.model;
      keys.forEach((key, n) => {
        const text = reply.translations[n];
        const why = text ? problem(wanted.get(key), text, lang) : 'refused by the service';
        if (why) {
          refused.push(`${key}: ${why}`);
        } else {
          put(fresh, key, text);
          fresh.source_hash[key] = hashOf(wanted.get(key));
          done++;
        }
      });
      save(lang, fresh); // a long run can stop and resume
      console.log(`${lang}: ${Math.min(i + BATCH, todo.length)}/${todo.length}, ${Math.round((Date.now() - started) / 1000)} s`);
    }
    console.log(`${lang}: translated ${done}, left in English ${refused.length}, already current ${wanted.size - outstanding.length}, waiting for review ${outstanding.length - todo.length}`);
    refused.forEach((r) => console.log(`  ${r}`));
  }
  if (bad) process.exit(1);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) await main(process.argv.slice(2));
