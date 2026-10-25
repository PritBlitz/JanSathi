// Shared checks for the review pipeline (docs/REVIEW.md): quote verification against source
// snapshots, content versions, and review status. Used by tools/review.mjs, tools/publish.mjs
// and tools/validate-content.mjs.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const readJson = (p) => JSON.parse(readFileSync(join(root, p), 'utf8'));
export const MIN_QUOTE = 20; // normalised characters

/** Normalises text for quote matching: case, Unicode forms, quotes, dashes, and all whitespace. */
export function normaliseForQuote(s) {
  return String(s)
    .normalize('NFKC')
    .toLowerCase()
    .replace(/\[page \d+\]/g, '')
    .replace(/[‘’‚′`´]/g, "'")
    .replace(/[“”„″]/g, '"')
    .replace(/[‐-―−]/g, '-')
    .replace(/[­​-‍﻿]/g, '')
    .replace(/[•▪●◦\s]+/g, '');
}

const snapshotCache = new Map();
export function snapshotText(contentHash) {
  if (!contentHash) return null;
  const hex = contentHash.replace(/^sha256:/, '');
  if (!snapshotCache.has(hex)) {
    const file = join(root, 'content', 'snapshots', `${hex}.txt`);
    snapshotCache.set(hex, existsSync(file) ? normaliseForQuote(readFileSync(file, 'utf8')) : null);
  }
  return snapshotCache.get(hex);
}

/** Every quote a scheme makes: rule quotes and supporting facts. */
export function quotesOf(scheme) {
  const out = [];
  for (const r of scheme.rules || []) out.push({ where: `rule ${r.code}`, source: r.source, quote: r.quote });
  for (const [i, f] of (scheme.facts || []).entries()) out.push({ where: `fact ${i} (${f.about || ''})`, source: f.source, quote: f.quote });
  return out;
}

/** Checks each quote appears verbatim in the snapshot of the source it cites. Returns problem strings. */
export function verifyQuotes(scheme, { requireAll = false } = {}) {
  const problems = [];
  const sources = new Map((scheme.sources || []).map((s) => [s.id, s]));
  for (const q of quotesOf(scheme)) {
    if (!q.quote) {
      if (requireAll) problems.push(`${q.where}: no quote`);
      continue;
    }
    const nq = normaliseForQuote(q.quote);
    if (nq.length < MIN_QUOTE) {
      problems.push(`${q.where}: quote too short to be evidence`);
      continue;
    }
    const src = sources.get(q.source);
    if (!src) {
      problems.push(`${q.where}: unknown source ${q.source}`);
      continue;
    }
    const text = snapshotText(src.content_hash);
    if (text === null) {
      problems.push(`${q.where}: no snapshot for source ${q.source} (${src.content_hash || 'no hash'})`);
      continue;
    }
    if (!text.includes(nq)) problems.push(`${q.where}: quote not found in source ${q.source}: "${q.quote.slice(0, 70)}…"`);
  }
  if (requireAll && !(scheme.facts || []).length) problems.push('facts: at least one quoted fact for the benefit is required');
  return problems;
}

const canonical = (v) =>
  Array.isArray(v) ? `[${v.map(canonical).join(',')}]`
  : v && typeof v === 'object' ? `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canonical(v[k])}`).join(',')}}`
  : JSON.stringify(v);

/** Hash of the reviewable content: everything except status and verification fields. */
export function contentVersion(scheme) {
  const copy = JSON.parse(JSON.stringify(scheme));
  delete copy.status;
  delete copy.verification;
  for (const s of copy.sources || []) {
    delete s.verified_at;
    delete s.verified_by;
  }
  return createHash('sha256').update(canonical(copy)).digest('hex').slice(0, 16);
}

export function loadReviews(slug) {
  const dir = join(root, 'content', 'reviews', slug);
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .map((f) => JSON.parse(readFileSync(join(dir, f), 'utf8')));
}

/** Review state of a scheme's current content. */
export function reviewStatus(scheme) {
  const version = contentVersion(scheme);
  const reviews = loadReviews(scheme.slug);
  const current = reviews.filter((r) => r.content_version === version);
  const approvals = current.filter((r) => r.verdict === 'approve');
  const reviewers = [...new Set(approvals.map((r) => r.reviewer))];
  const blockers = current.filter((r) => r.verdict !== 'approve');
  return { version, reviews, current, approvals, reviewers, blockers, approved: reviewers.length >= 2 && blockers.length === 0 };
}

export function allSchemes() {
  return readdirSync(join(root, 'content', 'schemes'))
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((f) => ({ file: `content/schemes/${f}`, scheme: readJson(`content/schemes/${f}`) }));
}
