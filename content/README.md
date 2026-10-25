# Content (knowledge base as code)

For the MVP, the knowledge base lives here as JSON files reviewed through pull requests. The API imports these files into Postgres, and `tools/build-bundle.mjs` turns them into the static bundle the PWA uses offline. A reviewer UI can replace this workflow later (see [docs/KNOWLEDGE_BASE.md](../docs/KNOWLEDGE_BASE.md)); the shape of the data stays the same.

```
content/
  fields.json          profile fields the rules may read, with bands and answer options
  documents.json       document types and generic "how to get it" guidance
  schemes/<slug>.json  one file per scheme: content, sources, rules, documents, deadlines
```

## Status of the current files

**Every scheme here is `draft`, and every source has `verified_at: null`.** On 2026-10-25 an AI session checked each rule, amount and document against the official source it cites, recorded the clause in `locator`, and filled `fetched_at` and `content_hash` (see the session log in [HANDOVER.md](../HANDOVER.md)). That check is not a human review. Until a reviewer re-checks each file against its sources and sets `verified_at`:

- the production bundle and the API leave them out (only `published` schemes are served);
- the PWA shows them only in preview mode, under a "Draft, not yet verified" warning, and every result is labelled "may be outdated".

Recent changes the files already reflect:

- **NSAP pensions** were renamed on 14 Oct 2026 (Pradhan Mantri Samajik Sahayata Yojana). The disability pension moves to 40% disability with no minimum age, at ₹375 / ₹625 a month; the revised guidelines are due within three months, so `igndps` rests on the Cabinet release (`govt_statement`, `guidance`) and has `reverify_days: 30`.
- **PMAY-G** uses the revised 10 exclusion criteria approved on 9 Aug 2024.
- **MGNREGA** was replaced by the VB-G RAM G Act on 1 Jul 2026 (`vb-g-ram-g`); job-card text says so.
- **Guideline periods that ended in March 2026** (PMMVY/Mission Shakti, pre-matric SC, PM-YASASVI): the files say so and a reviewer must confirm the current period.
- **Left out on purpose:** Stand-Up India (no extension beyond 2025 found) and NMMSS (needs class 8 at a government school, class 7 marks and a selection test, which the questionnaire cannot capture).

## Schemes (28)

Pensions: `ignoaps`, `ignwps`, `igndps`, `atal-pension`, `pm-sym`, `pm-kmy`. Insurance: `pmjjby`, `pmsby`. Farming: `pm-kisan`, `pmfby`, `kcc`. Health: `pm-jay`. Housing: `pmay-g`. Food: `pmgkay`. Work and livelihoods: `vb-g-ram-g`, `pm-vishwakarma`, `pm-svanidhi`. Energy: `pmuy`, `pm-surya-ghar`. Family support: `pm-parivar-labh`. Women and children: `pmmvy`, `jsy`, `sukanya-samriddhi`. Scholarships: `pre-matric-sc`, `post-matric-sc`, `pre-matric-st`, `post-matric-st`, `post-matric-obc`.

## Publishing a scheme

1. Open the official source, check every rule, amount, document and date against it, and fix the file.
2. Fill in `published_date`, `locator` and `source` references; set `fetched_at` and `content_hash` (from `python ingest/recheck.py --update <slug>`), `verified_at` (today) and `verified_by` (your reviewer id).
3. Add or update `rules/<slug>.cases.json` with the edge cases in [docs/RULES_ENGINE.md](../docs/RULES_ENGINE.md#8-testing).
4. Set `"status": "published"` and open a PR. **A second reviewer must approve it.** Nobody approves their own content.
5. CI checks the copy lint, that every rule/document/deadline references a source, that `confirmed` rules rest only on `gazette` or `guideline` sources, and that the fixtures pass (`npm run check`).

## Writing rules

- Rules use the json-logic subset in [docs/RULES_ENGINE.md](../docs/RULES_ENGINE.md): `var`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `in`, `and`, `or`, `!`.
- Only fields from `fields.json` may be read.
- One condition per rule where possible; use `or` only for real alternatives in the source (e.g. "aged 70+ **or** on the list").
- Rule text (`text.en`, `text.hi`) is a short plain-language statement, e.g. "Age 60 or older". It is shown in results and read aloud.

## Copy rules

Never "you are eligible", "you will get", "guaranteed" (or their Hindi equivalents). Describe what the scheme gives in the third person: "The scheme gives ₹6,000 a year to eligible farmer families." `npm run lint:copy` checks this.
