// Pure helpers for deadlines, scheme relations, reminders and application tracking. No DOM, so the
// same code runs in the app and in tools/test-features.mjs. Nothing here decides eligibility: the
// rules engine does. These only arrange what the content records, each entry with its source.

export const APPLICATION_STATUSES = ['planned', 'applied', 'under_review', 'approved', 'rejected'];
export const REASON_CODES = ['documents_missing', 'income_limit', 'age_limit', 'not_resident', 'duplicate', 'details_mismatch', 'other'];
export const PREFS_CACHE = 'jansathi-prefs'; // holds the reminder wording; see sw.js
export const REMINDER_EVERY_DAYS = 7;
export const DEADLINE_WARN_DAYS = 14;

const dayNumber = (iso) => Math.floor(Date.parse(`${iso.slice(0, 10)}T00:00:00Z`) / 86400000);
export const daysBetween = (from, to) => dayNumber(to) - dayNumber(from);

/** Deadlines that apply to this state and have not closed, soonest first. */
export function deadlinesFor(scheme, state, today) {
  return (scheme.deadlines || [])
    .filter((d) => !d.state_code || !state || d.state_code === state)
    .filter((d) => !d.closes_at || daysBetween(today, d.closes_at) >= 0)
    .sort((a, b) => (a.closes_at || '9999').localeCompare(b.closes_at || '9999'));
}

/**
 * Relations between schemes the person is likely to fit. Each pair appears once, even when both
 * scheme files record it. The note never picks one for the person (RULES_ENGINE: stacking).
 */
export function relationsAmong(schemes, results) {
  const likely = new Set(results.filter((r) => r.result === 'LIKELY_ELIGIBLE').map((r) => r.scheme));
  const seen = new Set();
  const out = [];
  for (const s of schemes) {
    if (!likely.has(s.slug)) continue;
    for (const rel of s.relations || []) {
      if (!likely.has(rel.with)) continue;
      const pair = [s.slug, rel.with].sort().join('|') + rel.kind;
      if (seen.has(pair)) continue;
      seen.add(pair);
      out.push({ kind: rel.kind, a: s.slug, b: rel.with, source: s.sources.find((x) => x.id === rel.source) || null, note: rel.note || null });
    }
  }
  return out.sort((x, y) => (x.kind === y.kind ? 0 : x.kind === 'excludes' ? -1 : 1));
}

/** Mandatory documents the person has not marked as "have", across the chosen schemes. */
export function missingDocuments(checklist, schemeBy) {
  const codes = new Set();
  for (const slug of checklist.schemes) {
    for (const d of schemeBy(slug)?.documents || []) {
      if (d.mandatory && checklist.have[d.code] !== true) codes.add(d.code);
    }
  }
  return [...codes];
}

/** What a reminder should say today, or null. At most one every REMINDER_EVERY_DAYS days. */
export function reminderDue({ enabled, last }, { checklist, schemeBy, state, today }) {
  if (!enabled) return null;
  if (last && daysBetween(last, today) < REMINDER_EVERY_DAYS) return null;
  const soon = [];
  for (const slug of checklist.schemes) {
    const s = schemeBy(slug);
    if (!s) continue;
    const d = deadlinesFor(s, state, today).find((x) => x.closes_at && daysBetween(today, x.closes_at) <= DEADLINE_WARN_DAYS);
    if (d) soon.push({ slug, closes_at: d.closes_at });
  }
  const missing = missingDocuments(checklist, schemeBy).length;
  return soon.length || missing ? { soon, missing } : null;
}

/** Keeps only a valid tracking entry. Digit runs are removed: a note must never hold a personal number. */
export function cleanApplication(entry, today) {
  if (!APPLICATION_STATUSES.includes(entry.status)) return null;
  const reason = entry.status === 'rejected' && entry.reason ? entry.reason.replace(/\d(?:[\s-]?\d){8,}/g, '').trim().slice(0, 300) : '';
  return { status: entry.status, reason: reason || null, updated: today };
}
