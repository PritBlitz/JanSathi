// Runs Lighthouse (mobile) on the built PWA and fails below the targets in DESIGN.md §10 and
// principle 6. Lighthouse is fetched by npx, so the root tooling keeps no dependencies.
// Usage: node tools/lighthouse.mjs   (after npm run build:web)
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const PORT = 8766;
// The performance score moves with machine load, so the gate is the DESIGN §10 budgets themselves.
const TARGETS = { accessibility: 100, 'best-practices': 95, seo: 95 };
const BUDGETS = { 'largest-contentful-paint': 2500, 'cumulative-layout-shift': 0.05 };
const dir = mkdtempSync(join(tmpdir(), 'jansathi-lh-'));
const out = join(dir, 'report.json');
const server = spawn(process.execPath, [join(root, 'tools/serve.mjs'), String(PORT)], { stdio: 'ignore' });
await new Promise((r) => setTimeout(r, 800));

const run = spawnSync('npx', ['--yes', 'lighthouse@12', `http://localhost:${PORT}/`, '--quiet', '--output=json', `--output-path=${out}`, '--chrome-flags=--headless=new --no-sandbox'], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
server.kill();

let failed = run.status !== 0;
if (!failed) {
  const report = JSON.parse(readFileSync(out, 'utf8'));
  for (const [id, target] of Object.entries(TARGETS)) {
    const score = Math.round(report.categories[id].score * 100);
    const ok = score >= target;
    failed ||= !ok;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${id}: ${score} (target ${target})`);
  }
  for (const ref of report.categories.accessibility.auditRefs) {
    const audit = report.audits[ref.id];
    if (ref.weight > 0 && audit.score !== null && audit.score < 1) {
      console.log(`     ${ref.id}: ${audit.title}`);
      for (const item of audit.details?.items?.slice(0, 5) || []) console.log(`       ${item.node?.selector || ''} ${item.node?.snippet?.slice(0, 120) || ''}`);
    }
  }
  for (const [id, max] of Object.entries(BUDGETS)) {
    const ok = report.audits[id].numericValue <= max;
    failed ||= !ok;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${id}: ${report.audits[id].displayValue} (budget ${max})`);
  }
  console.log(`     performance score ${Math.round(report.categories.performance.score * 100)}, total blocking time ${report.audits['total-blocking-time'].displayValue}`);
}
rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
process.exit(failed ? 1 : 0);
