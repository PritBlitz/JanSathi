# Review and publishing

> **Decided 2026-10-25 (maintainer):** reviews are done by independent AI reviewer agents. A human reviewer can review the same way and is recorded as such. The app tells users who checked each scheme.

Nothing reaches users as "checked" until **two independent reviewers** have approved the **exact current content** of a scheme, and every quote it relies on has been found, mechanically, in the saved text of the official source.

## 1. Why this is checkable

- Each rule and benefit fact carries a **verbatim quote** from its source. `content/snapshots/<hash>.txt` holds the text of that source, named by the same hash recorded in the scheme. `tools/lib/quality.mjs` checks every quote is really in that text (ignoring whitespace and typography). A reviewer can't approve a rule whose evidence isn't there.
- Each review records the scheme's **content version** (a hash of everything except status and verification fields). If anyone changes the scheme afterwards, old reviews no longer count, and a published scheme goes back to `needs_review`.
- Reviewers work **independently**: separate agent instances, no access to each other's reviews, and they don't edit scheme files.

## 2. What a reviewer checks

For each scheme (`content/schemes/<slug>.json`):

1. `node tools/review.mjs version <slug>`: note the version.
2. **Sources:** official publisher; the URL works or the snapshot matches the recorded hash; the document is current. Search the department's site and PIB for anything newer (2025–2026 orders, budget changes, renaming, discontinuation).
3. **Every rule:** the logic says what the clause says (field meaning, `<` vs `<=`, numbers, units); the quote supports it; `confidence` fits the source type; the residence rule is present for state schemes.
4. **Content:** summary, benefit and how-to-apply match the sources; every amount has a quoted fact; no promises ("you will get"); plain language. Check that the Hindi and state-language text means the same as the English.
5. **Documents** are what the source asks for.
6. **Test cases** look right for the rules (the engine itself is tested separately).
7. `node tools/review.mjs quotes <slug>` reports no problems.

## 3. Review record

One file per reviewer per scheme: `content/reviews/<slug>/<reviewer>.json` (reviewer ids like `ai-r1`; a person uses `human-<name>`).

```json
{
  "slug": "tn-example",
  "reviewer": "ai-r1",
  "reviewer_kind": "ai",
  "date": "2026-10-25",
  "content_version": "3f2a9c0d1e4b5a67",
  "verdict": "approve",
  "sources_checked": [ { "id": "guidelines", "content_hash": "sha256:…", "method": "refetched" } ],
  "newer_changes_search": "Searched tn.gov.in and PIB for 2025-2026 changes: none found.",
  "findings": [ { "target": "rule:income_max", "severity": "minor", "note": "…", "suggestion": "…" } ]
}
```

- `verdict`: `approve` (no blockers) or `changes_requested`.
- `severity`: `blocker` (wrong rule or amount, unofficial or outdated source, quote not found, promise language, discontinued scheme) or `minor`.
- `method`: `refetched` (fetched again and hash matched), `snapshot` (host blocks bots; checked against the saved text), `changed` (the live source differs from the recorded hash: always a blocker), `counters_only` (`ingest/recheck.py` finds that only numbers differ, such as running totals or a footer build label, and a reviewer confirms no quoted text moved: not a blocker).

## 4. Publishing

```bash
node tools/review.mjs          # table: version, approvals, change requests, quote check
node tools/publish.mjs         # publish what is ready; demote what changed
npm run build                  # rebuild frontend/public/data/
```

`publish.mjs` publishes a scheme when it has ≥ 2 `approve` reviews from different reviewers on the current version, no `changes_requested` on that version, and every quote verified. It sets `status: published`, the cited sources' `verified_at` / `verified_by`, and:

```json
"verification": { "method": "ai-dual-review", "reviewers": ["ai-r1", "ai-r2"], "date": "2026-10-25", "content_version": "…" }
```

## 5. What users see

| `verification.method` | Label on the scheme | Explanation shown |
|---|---|---|
| `ai-dual-review` | "Checked by AI reviewers" | Two independent AI reviewers compared it with its official sources on {date}; a person has not checked it yet; check with the office before applying. |
| `human-review`, `human-and-ai-review` | "Verified by a reviewer" | Planned. |
| none (draft) | "Draft, not yet verified" (preview mode only) | |

The re-check job (`ingest/recheck.py`) still runs on published schemes: a changed source marks the scheme for review, and stale facts carry the "may be outdated" warning (RULES_ENGINE §9.5).
