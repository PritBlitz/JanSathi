// Checks that all 10 locale files have identical key sets and the same {placeholders},
// and that every field and option in content/fields.json has its question and option labels.
// Usage: node tools/check-locales.mjs   (exit 1 on any problem)
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const CODES = ['en', 'hi', 'bn', 'ta', 'te', 'mr', 'gu', 'kn', 'ml', 'pa'];
const problems = [];

const flatten = (obj, prefix = '', out = {}) => {
  for (const [k, v] of Object.entries(obj)) {
    if (k.startsWith('$')) continue;
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object') flatten(v, key, out);
    else out[key] = v;
  }
  return out;
};
const placeholders = (s) => [...String(s).matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(',');

const locales = {};
for (const code of CODES) {
  const file = join(root, 'web/locales', `${code}.json`);
  if (!existsSync(file)) {
    problems.push(`web/locales/${code}.json is missing`);
    continue;
  }
  locales[code] = flatten(JSON.parse(readFileSync(file, 'utf8')));
}

const en = locales.en || {};
for (const [code, keys] of Object.entries(locales)) {
  if (code === 'en') continue;
  for (const k of Object.keys(en)) if (!(k in keys)) problems.push(`${code}: missing key ${k}`);
  for (const k of Object.keys(keys)) if (!(k in en)) problems.push(`${code}: extra key ${k} (not in en)`);
  for (const k of Object.keys(en)) {
    if (k in keys && placeholders(en[k]) !== placeholders(keys[k])) problems.push(`${code}: ${k} has placeholders {${placeholders(keys[k])}}, en has {${placeholders(en[k])}}`);
  }
  for (const [k, v] of Object.entries(keys)) if (typeof v === 'string' && !v.trim()) problems.push(`${code}: ${k} is empty`);
}

// Every profile field needs q.<field>; enum and band options need opt.<field>.<value>; bools use opt.bool.*.
const fields = JSON.parse(readFileSync(join(root, 'content/fields.json'), 'utf8')).fields;
for (const [name, def] of Object.entries(fields)) {
  if (!(`q.${name}` in en)) problems.push(`en: missing key q.${name}`);
  for (const o of def.options || []) if (!(`opt.${name}.${o.value}` in en)) problems.push(`en: missing key opt.${name}.${o.value}`);
}

// Every non-English locale must say which keys a native speaker has reviewed.
const statusFile = join(root, 'web/locales/review-status.json');
if (!existsSync(statusFile)) problems.push('web/locales/review-status.json is missing');

if (problems.length) {
  console.error(problems.join('\n'));
  console.error(`\n${problems.length} locale problem(s)`);
  process.exit(1);
}
console.log(`locales OK: ${CODES.length} languages, ${Object.keys(en).length} keys each`);
