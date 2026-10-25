// Review pipeline status (docs/REVIEW.md).
//   node tools/review.mjs                 table of every scheme: status, version, approvals, quote check
//   node tools/review.mjs version <slug>  content version a reviewer must record
//   node tools/review.mjs quotes [slug]   quote check details
import { allSchemes, reviewStatus, verifyQuotes, contentVersion } from './lib/quality.mjs';

const [cmd, arg] = process.argv.slice(2);
const list = allSchemes().map((x) => x.scheme);

if (cmd === 'version') {
  const s = list.find((x) => x.slug === arg);
  if (!s) process.exit(console.error(`no scheme ${arg}`) || 2);
  console.log(contentVersion(s));
} else if (cmd === 'quotes') {
  let bad = 0;
  for (const s of list.filter((x) => !arg || x.slug === arg)) {
    const p = verifyQuotes(s, { requireAll: true });
    if (p.length) bad++;
    console.log(`${p.length ? '✗' : '✓'} ${s.slug}${p.length ? '\n   ' + p.join('\n   ') : ''}`);
  }
  process.exit(bad ? 1 : 0);
} else {
  const rows = list.map((s) => {
    const r = reviewStatus(s);
    const q = verifyQuotes(s, { requireAll: true });
    return { slug: s.slug, status: s.status, version: r.version, approvals: r.reviewers.length, changes: r.blockers.length, quotes: q.length ? `${q.length} problem(s)` : 'ok', ready: r.approved && !q.length };
  });
  console.table(rows);
  const ready = rows.filter((r) => r.ready && r.status !== 'published').length;
  console.log(`${rows.filter((r) => r.status === 'published').length} published, ${ready} ready to publish (node tools/publish.mjs), ${rows.length} total`);
}
