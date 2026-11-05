// Fails when what a first visit downloads grows past the limits in DESIGN.md §10. Run after a build.
// JS is held at its measured level, not the 60 KB target: React alone is about 58 KB (DESIGN §10).
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'frontend/dist');
const html = readFileSync(join(dist, 'index.html'), 'utf8');
const gz = (file) => gzipSync(readFileSync(file)).length;
const linked = (re) => [...html.matchAll(re)].map((m) => join(dist, m[1].replace(/^\.\//, '')));

const LIMITS = { 'first-load JS': 88, CSS: 25, 'Latin fonts': 150 };
const js = linked(/<script[^>]+src="([^"]+\.js)"/g).reduce((n, f) => n + gz(f), 0);
const css = linked(/<link[^>]+href="([^"]*assets[^"]+\.css)"/g).reduce((n, f) => n + gz(f), 0);
const latin = ['anton-latin', 'jakarta-latin', 'bricolage-latin'];
const fonts = readdirSync(join(dist, 'fonts')).filter((f) => latin.some((l) => f.startsWith(l))).reduce((n, f) => n + statSync(join(dist, 'fonts', f)).size, 0);

let failed = false;
for (const [name, bytes] of [['first-load JS', js], ['CSS', css], ['Latin fonts', fonts]]) {
  const kb = bytes / 1024;
  const ok = bytes > 0 && kb <= LIMITS[name];
  failed ||= !ok;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}: ${kb.toFixed(1)} KB (limit ${LIMITS[name]} KB)`);
}
process.exit(failed ? 1 : 0);
