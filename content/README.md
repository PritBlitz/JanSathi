# Content (knowledge base as code)

For the MVP, the knowledge base lives here as JSON files reviewed through pull requests. The API imports these files into Postgres, and `tools/build-bundle.mjs` turns them into the static bundle the PWA uses offline. A reviewer UI can replace this workflow later (see [docs/KNOWLEDGE_BASE.md](../docs/KNOWLEDGE_BASE.md)); the shape of the data stays the same.

```
content/
  fields.json          profile fields the rules may read, with bands and answer options
  documents.json       document types and generic "how to get it" guidance
  schemes/<slug>.json  one file per scheme: content, sources, rules, documents, deadlines
```

## Status of the current files

**Every scheme here is `draft`, and every source has `verified_at: null`.** The files were drafted from public knowledge of each scheme and point at the official portals, but nobody has yet checked each rule against the official text. Until a reviewer does:

- the production bundle and the API leave them out (only `published` schemes are served);
- the PWA shows them only in preview mode, under a "Draft, not yet verified" warning.

## Publishing a scheme

1. Open the official source, check every rule, amount, document and date against it, and fix the file.
2. Fill in `published_date`, `locator` and `source` references; set `fetched_at` and `content_hash` (from `python ingest/recheck.py --update <slug>`), `verified_at` (today) and `verified_by` (your reviewer id).
3. Add or update `rules/<slug>.cases.json` with the edge cases in [docs/RULES_ENGINE.md](../docs/RULES_ENGINE.md#8-testing).
4. Set `"status": "published"` and open a PR. **A second reviewer must approve it.** Nobody approves their own content.
5. CI checks the copy lint, that every rule/document/deadline references a source, that `confirmed` rules don't rest on `secondary` or `faq` sources, and that the fixtures pass.

## Writing rules

- Rules use the json-logic subset in [docs/RULES_ENGINE.md](../docs/RULES_ENGINE.md): `var`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `in`, `and`, `or`, `!`.
- Only fields from `fields.json` may be read.
- One condition per rule where possible; use `or` only for real alternatives in the source (e.g. "aged 70+ **or** on the list").
- Rule text (`text.en`, `text.hi`) is a short plain-language statement, e.g. "Age 60 or older". It is shown in results and read aloud.

## Copy rules

Never "you are eligible", "you will get", "guaranteed" (or their Hindi equivalents). Describe what the scheme gives in the third person: "The scheme gives ₹6,000 a year to eligible farmer families." `npm run lint:copy` checks this.
