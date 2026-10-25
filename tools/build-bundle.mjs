// Builds the static rules bundle the PWA uses offline (shape: docs/RULES_ENGINE.md §9.7).
//   node tools/build-bundle.mjs                   -> web/data/bundle.json (published schemes only)
//   node tools/build-bundle.mjs --include-drafts  -> web/data/bundle.preview.json (all schemes)
//   add --check to fail instead of writing when the file is out of date (for CI)
// The file is rewritten only when its content changes, so generated_at stays stable.
import { readFileSync, readdirSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const args = new Set(process.argv.slice(2));
const includeDrafts = args.has('--include-drafts');
const readJson = (p) => JSON.parse(readFileSync(join(root, p), 'utf8'));

const fields = readJson('content/fields.json');
const documents = readJson('content/documents.json');
const schemes = readdirSync(join(root, 'content/schemes'))
  .filter((f) => f.endsWith('.json'))
  .sort()
  .map((f) => readJson(`content/schemes/${f}`))
  .filter((s) => s.status !== 'archived' && (includeDrafts || s.status === 'published'));

const body = { include_drafts: includeDrafts, fields, documents, schemes };
const version = createHash('sha256').update(JSON.stringify(body)).digest('hex');
const outFile = join(root, 'web/data', includeDrafts ? 'bundle.preview.json' : 'bundle.json');

let previous = null;
if (existsSync(outFile)) {
  try {
    previous = JSON.parse(readFileSync(outFile, 'utf8'));
  } catch {
    previous = null;
  }
}
if (previous?.version === version) {
  console.log(`${outFile.slice(root.length + 1)} is up to date (${schemes.length} schemes)`);
  process.exit(0);
}
if (args.has('--check')) {
  console.error(`${outFile.slice(root.length + 1)} is out of date: run npm run build`);
  process.exit(1);
}
const bundle = { version, generated_at: new Date().toISOString().slice(0, 10), ...body };
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, JSON.stringify(bundle) + '\n');
console.log(`wrote ${outFile.slice(root.length + 1)}: ${schemes.length} schemes, version ${version.slice(0, 12)}`);
