// Publishes every scheme whose current content has two approving reviews from different
// reviewers, verified quotes, and snapshots for every cited source (docs/REVIEW.md).
// Sets status, the sources' verified_at / verified_by, and the scheme's verification record.
// A scheme whose content changed after it was published goes back to needs_review.
// Usage: node tools/publish.mjs [--dry-run]
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { root, allSchemes, citedSources, reviewStatus, verifyQuotes } from './lib/quality.mjs';

const dry = process.argv.includes('--dry-run');
let published = 0;
let demoted = 0;
for (const { file, scheme } of allSchemes()) {
  const r = reviewStatus(scheme);
  const quoteProblems = verifyQuotes(scheme, { requireAll: true });
  const ok = r.approved && !quoteProblems.length;
  const stillCurrent = scheme.verification?.content_version === r.version;

  if (scheme.status === 'published' && !stillCurrent) {
    scheme.status = 'needs_review';
    demoted++;
    console.log(`↓ ${scheme.slug}: content changed since publication, now needs_review`);
  } else if (ok && !(scheme.status === 'published' && stillCurrent)) {
    const date = r.approvals.map((a) => a.date).sort().at(-1);
    const kinds = new Set(r.approvals.map((a) => a.reviewer_kind || 'ai'));
    const by = r.reviewers.join(', ');
    scheme.status = 'published';
    scheme.verification = {
      method: kinds.has('human') ? (kinds.size > 1 ? 'human-and-ai-review' : 'human-review') : 'ai-dual-review',
      reviewers: r.reviewers,
      date,
      content_version: r.version,
    };
    const cited = citedSources(scheme);
    for (const s of scheme.sources) {
      if (cited.has(s.id)) {
        s.verified_at = date;
        s.verified_by = by;
      }
    }
    published++;
    console.log(`✓ ${scheme.slug}: published (${scheme.verification.method}, ${by})`);
  } else if (!ok && scheme.status !== 'published') {
    const why = [!r.approved && `${r.reviewers.length}/2 approvals${r.blockers.length ? `, ${r.blockers.length} change request(s)` : ''}`, quoteProblems.length && `${quoteProblems.length} quote problem(s)`].filter(Boolean).join('; ');
    console.log(`· ${scheme.slug}: not ready (${why})`);
    continue;
  } else continue;
  if (!dry) writeFileSync(join(root, file), JSON.stringify(scheme, null, 2) + '\n');
}
console.log(`${published} published, ${demoted} sent back to review${dry ? ' (dry run, nothing written)' : ''}`);
