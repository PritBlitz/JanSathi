# Content authoring guide

How to add a scheme to `content/`. Written for people **and** for AI author agents; both follow exactly the same rules. A scheme is published only after two independent reviews ([REVIEW.md](REVIEW.md)).

## 1. Ground rules

1. **Only official sources.** Central or state government sites (`*.gov.in`, `*.nic.in`, a state portal run by the department), the Gazette, PIB, or official scheme guidelines. News, blogs, aggregators and myScheme copies can help you *find* a source but are never cited.
2. **Never invent.** Every rule, amount, date and document must be in a source you saved. If you can't find it, leave it out and say so in your report.
3. **Current.** Today is the date you work. Look for 2025–2026 changes (budget speeches, Cabinet decisions, revised guidelines, renamed schemes). If a scheme was discontinued or merged, don't add it.
4. **Quote everything.** Each rule has a `quote`: a verbatim sentence or phrase from the source snapshot (at least ~20 characters) that supports it. Each benefit amount has a `facts` entry with a quote. `node tools/review.mjs quotes <slug>` checks every quote against the snapshot; a quote that isn't found is a blocker.
5. **Copy rules** ([CONTRIBUTING.md](CONTRIBUTING.md) §3): grade-5 language, third person ("The scheme gives …"), never "you are eligible", "you will get", "guaranteed" or their equivalents in any language. `node tools/lint-copy.mjs` checks this.
6. **Draft only.** Authors set `"status": "draft"` and never set `verified_at`, `verified_by` or `verification`. `tools/publish.mjs` does that after review.

## 2. Files

| File | What |
|---|---|
| `content/schemes/<slug>.json` | The scheme. State schemes use the slug `<state_code>-<name>`, e.g. `mh-ladki-bahin`. |
| `rules/<slug>.cases.json` | Test cases for its rules (§6). |
| `content/snapshots/<hash>.txt` | Extracted text of each source, written by `ingest/recheck.py --snapshot` (§4). |
| `content/proposals/<author>.json` | New profile fields or document types you need (§5). Don't edit `fields.json` or `documents.json` directly. |

State codes are the `state` options in `content/fields.json` (`up`, `mh`, `tn`, …).

## 3. Scheme file

Copy the shape of an existing file: [`content/schemes/pre-matric-sc.json`](../content/schemes/pre-matric-sc.json) (guideline-based) or [`content/schemes/pmuy.json`](../content/schemes/pmuy.json) (portal-based). Required keys:

```jsonc
{
  "slug": "tn-example",
  "status": "draft",
  "level": "state",                 // "central" for Government of India schemes
  "state_code": "tn",               // null for central schemes
  "category": "education",          // pension, insurance, agriculture, health, housing, food, employment,
                                    // energy, family_support, women_children, disability, education
  "icon": "🎓",
  "ministry": { "en": "…", "hi": "…" },          // the state department
  "official_apply_url": "https://…",             // official page where people apply or learn how
  "reverify_days": 180,                          // 90 for yearly scholarships, 30–60 if changes are expected
  "content": {
    "en": { "name": "…", "summary": "…", "benefit": "…", "how_to_apply": "…" },
    "hi": { … same keys … },
    "ta": { … same keys, in the state's own language … }
  },
  "sources": [ { "id": "guidelines", "url": "https://…", "title": "…", "publisher": "…",
                 "doc_type": "guideline", "lang": "en", "published_date": "2025-04",
                 "fetched_at": null, "content_hash": null, "verified_at": null, "verified_by": null } ],
  "rules": [
    { "code": "resident", "logic": { "==": [ { "var": "state" }, "tn" ] }, "hard": true,
      "confidence": "guidance", "source": "guidelines", "locator": "§3 Eligibility",
      "quote": "The applicant should be a native of Tamil Nadu",
      "text": { "en": "Lives in Tamil Nadu", "hi": "तमिलनाडु में रहते हैं" } }
  ],
  "facts": [ { "about": "amount", "source": "guidelines", "quote": "Rs.1,000 per month will be paid" } ],
  "documents": [ { "code": "aadhaar", "mandatory": true, "source": "guidelines" } ],
  "changes": [ { "date": "2026-04-01", "source": "guidelines", "text": { "en": "The amount rose from ₹500 to ₹1,000 a month.", "hi": "…" } } ],
  "window": { "type": "rolling" },   // rolling | yearly | seasonal
  "deadlines": [],
  "relations": []
}
```

- **Every state scheme needs the `resident` rule** above (exact logic `{"==":[{"var":"state"},"<code>"]}`), with a quote about residence or domicile if the source has one.
- `content` needs `en` and `hi`; state schemes should also have the state's main language (`bn`, `ta`, `te`, `mr`, `gu`, `kn`, `ml`, `pa`). For other states (e.g. Assam, Odisha, the North East) `en` + `hi` is enough.
- `changes` is optional: add real, dated changes you found (with a source).

### Rules

- One condition per rule. Operators: `var`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `in`, `and`, `or`, `!` ([RULES_ENGINE.md](RULES_ENGINE.md) §9). Band fields (`age`, `annual_income`, `disability_percent`, `last_exam_percent`) only with `<`, `<=`, `>`, `>=`.
- Use only fields from `content/fields.json`. Read the field's question in `frontend/locales/en.json` (`q.<field>`) and make sure the rule means the same thing.
- Watch the boundaries: "up to ₹2.5 lakh" is `<=`, "below ₹8 lakh" is `<`, "60 years and above" is `>=`.
- `confidence`: `confirmed` only for gazette/guideline clauses; `guidance` for portals, government statements and FAQs; `uncertain` when the source is unclear or the profile can't express the condition fully (a failing `uncertain` rule shows "check with the office" instead of "not eligible").
- `hard: false` only for priority or preference criteria.

## 4. Sources, hashes and snapshots

```bash
python -E -P ingest/recheck.py --update <slug> --snapshot
```

fetches every source, records `fetched_at` and `content_hash`, and saves the text to `content/snapshots/`. It respects robots.txt and waits 5 s between requests to a host.

**Hosts that block bots** (403, or disallowed in robots.txt): download the page or PDF yourself into your scratch folder, then

```bash
python -E -P ingest/recheck.py --hash-file <saved file> --snapshot
```

prints the hash and writes the snapshot. Put the hash in `content_hash`, today in `fetched_at`, and add `"fetch": "manual", "fetch_note": "<why>"` to the source.

**Scanned PDFs** have no text, so quotes can't be checked. Prefer a text source (the department's HTML page, a text PDF of the same order).

## 5. Proposals (new fields and documents)

If a scheme needs a condition no field covers (for example "has passed class 12"), add it to `content/proposals/<author>.json`:

```json
{ "fields": { "passed_class_12": { "type": "bool", "scope": "member", "icon": "🎓",
                "question_en": "Have you passed class 12?", "why": "tn-example needs it" } },
  "documents": { "domicile_certificate": { "icon": "📄", "name": { "en": "…", "hi": "…" },
                 "how_to": { "en": "…", "hi": "…" }, "office": { "en": "…", "hi": "…" },
                 "portal_url": "https://…", "source": { "url": "https://…", "publisher": "…", "doc_type": "portal" } } } }
```

You may use a proposed field in rules; run the validator with `--allow-proposals` until the maintainer merges it. Reuse existing fields whenever the meaning matches.

## 6. Test cases

`rules/<slug>.cases.json`: an array of `{ name, profile, skipped: [], as_of, expect }` ([RULES_ENGINE.md](RULES_ENGINE.md) §9.7). Include:

- a profile that passes every rule (put `"stale": true, "label": "uncertain"` in its `expect`: drafts are always stale);
- just below / at / just above every number (e.g. ages 17/18, incomes 249999/250000/250001);
- a band that straddles a limit (expects `missing_fields` for that field);
- the residence rule failing (another state) and the state missing;
- a missing field. The scheme's `missing_fields` follow **rule order**.

## 7. Before you finish

```bash
node tools/validate-content.mjs --allow-proposals   # schema, sources, quotes present must match
node tools/review.mjs quotes <slug>                 # every rule and fact quoted and found
node tools/lint-copy.mjs
node --test tools/test-rules.mjs                    # your cases pass
```
