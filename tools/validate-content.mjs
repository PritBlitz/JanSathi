// Validates content/: fields, documents and every scheme file.
// Usage: node tools/validate-content.mjs   (exit 1 on any error)
// Rules checked: docs/KNOWLEDGE_BASE.md §1, docs/RULES_ENGINE.md §1 and §9, content/README.md.
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateLogic } from '../web/js/rules.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const readJson = (p) => JSON.parse(readFileSync(join(root, p), 'utf8'));

const STATUSES = ['draft', 'published', 'needs_review', 'archived'];
const DOC_TYPES = ['gazette', 'guideline', 'govt_statement', 'faq', 'portal', 'secondary'];
const CONFIRMED_OK = ['gazette', 'guideline'];
const CONFIDENCES = ['confirmed', 'guidance', 'uncertain'];
const FIELD_TYPES = ['band', 'bool', 'enum'];
const CONTENT_KEYS = ['name', 'summary', 'benefit', 'how_to_apply'];
const LANGS = ['en', 'hi'];
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

const errors = [];
const err = (where, msg) => errors.push(`${where}: ${msg}`);
const bilingual = (where, obj) => {
  for (const l of LANGS) if (!obj || typeof obj[l] !== 'string' || !obj[l].trim()) err(where, `missing ${l} text`);
};

// fields.json
const fieldsFile = readJson('content/fields.json');
const fields = fieldsFile.fields;
for (const [name, def] of Object.entries(fields)) {
  if (!FIELD_TYPES.includes(def.type)) err(`fields.${name}`, `unknown type ${def.type}`);
  if (!['member', 'household'].includes(def.scope)) err(`fields.${name}`, 'scope must be member or household');
  if (def.type !== 'bool' && !Array.isArray(def.options)) err(`fields.${name}`, 'band and enum fields need options');
  for (const o of def.options || []) {
    if (def.type === 'band' && !o.unknown && !(o.band && o.band.min <= o.band.max)) err(`fields.${name}.${o.value}`, 'band option needs {min <= max}');
  }
}
for (const f of fieldsFile.core) if (!fields[f]) err('fields.core', `unknown field ${f}`);

// documents.json
const documents = readJson('content/documents.json').documents;
for (const [code, d] of Object.entries(documents)) {
  bilingual(`documents.${code}.name`, d.name);
  bilingual(`documents.${code}.how_to`, d.how_to);
  if (d.source && !DOC_TYPES.includes(d.source.doc_type)) err(`documents.${code}`, `unknown doc_type ${d.source.doc_type}`);
}

// schemes
const files = readdirSync(join(root, 'content/schemes')).filter((f) => f.endsWith('.json'));
for (const file of files) {
  const s = readJson(`content/schemes/${file}`);
  const at = `schemes/${file}`;
  if (s.slug !== file.slice(0, -5)) err(at, `slug ${s.slug} does not match the file name`);
  if (!STATUSES.includes(s.status)) err(at, `unknown status ${s.status}`);
  if (!['central', 'state'].includes(s.level)) err(at, 'level must be central or state');
  if (s.level === 'state' && !s.state_code) err(at, 'a state scheme needs state_code');
  if (!/^https:\/\//.test(s.official_apply_url || '')) err(at, 'official_apply_url must be https');
  if (!Number.isInteger(s.reverify_days) || s.reverify_days < 1) err(at, 'reverify_days must be a positive integer');
  bilingual(`${at} ministry`, s.ministry);
  for (const l of LANGS) for (const k of CONTENT_KEYS) {
    if (!s.content?.[l]?.[k]?.trim()) err(at, `content.${l}.${k} is missing`);
  }

  const sources = new Map();
  for (const src of s.sources || []) {
    const sat = `${at} source ${src.id}`;
    if (sources.has(src.id)) err(sat, 'duplicate source id');
    sources.set(src.id, src);
    if (!/^https:\/\//.test(src.url || '')) err(sat, 'url must be https');
    if (!DOC_TYPES.includes(src.doc_type)) err(sat, `unknown doc_type ${src.doc_type}`);
    if (src.doc_type === 'secondary') err(sat, 'secondary sources may not be cited to users');
    for (const k of ['fetched_at', 'verified_at']) if (src[k] !== null && src[k] !== undefined && !ISO_DATE.test(src[k])) err(sat, `${k} must be YYYY-MM-DD or null`);
    if (src.content_hash && !/^sha256:[0-9a-f]{64}$/.test(src.content_hash)) err(sat, 'content_hash must be sha256:<64 hex>');
    if (src.fetch === 'manual' && !src.fetch_note) err(sat, 'a manual source needs fetch_note');
    if (src.verified_at && !src.verified_by) err(sat, 'verified_at needs verified_by');
  }
  const cites = (where, id) => {
    if (!sources.has(id)) err(where, `cites unknown source ${id}`);
    return sources.get(id);
  };

  const codes = new Set();
  for (const r of s.rules || []) {
    const rat = `${at} rule ${r.code}`;
    if (codes.has(r.code)) err(rat, 'duplicate rule code');
    codes.add(r.code);
    if (typeof r.hard !== 'boolean') err(rat, 'hard must be true or false');
    if (!CONFIDENCES.includes(r.confidence)) err(rat, `unknown confidence ${r.confidence}`);
    if (!r.locator) err(rat, 'locator (the clause the rule rests on) is missing');
    bilingual(`${rat} text`, r.text);
    for (const e of validateLogic(r.logic, fields)) err(rat, e);
    const src = cites(rat, r.source);
    if (src && r.confidence === 'confirmed' && !CONFIRMED_OK.includes(src.doc_type)) {
      err(rat, `a confirmed rule must rest on a gazette or guideline, not ${src.doc_type}`);
    }
  }
  for (const d of s.documents || []) {
    if (!documents[d.code]) err(`${at} document ${d.code}`, 'unknown document code');
    cites(`${at} document ${d.code}`, d.source);
  }
  for (const [i, d] of (s.deadlines || []).entries()) cites(`${at} deadline ${i}`, d.source);
  for (const [i, rel] of (s.relations || []).entries()) cites(`${at} relation ${i}`, rel.source);

  if (s.status === 'published') {
    const used = new Set([...(s.rules || []).map((r) => r.source), ...(s.documents || []).map((d) => d.source)]);
    for (const id of used) {
      const src = sources.get(id);
      if (src && (!src.verified_at || !src.fetched_at || !src.content_hash)) {
        err(`${at} source ${id}`, 'a published scheme needs fetched_at, content_hash and verified_at on every cited source');
      }
    }
  }
}

if (errors.length) {
  console.error(errors.join('\n'));
  console.error(`\n${errors.length} problem(s) in content/`);
  process.exit(1);
}
console.log(`content OK: ${Object.keys(fields).length} fields, ${Object.keys(documents).length} documents, ${files.length} schemes`);
