# Knowledge base

> **Status (updated 2026-11-04):** content lives as reviewed files in `content/` (not yet a database); `ingest/recheck.py` re-fetches and hashes sources and runs daily in GitHub Actions (`.github/workflows/recheck.yml`), writing `content/source-status.json` (§5) and opening an issue when a person is needed; "Report outdated info" goes to the API's `report` table (`POST /api/v1/reports`, moderated via `/api/v1/admin/reports`). Chunking, embeddings and the reviewer UI are **planned**.

The knowledge base is the verified, structured store of schemes, rules, documents, deadlines and their sources. Nothing reaches a user unless a human reviewer has checked it against an official source.

## 1. Source types

| `doc_type` | What it is | Example | Can back a `confirmed` rule? |
|---|---|---|---|
| `gazette` | Official notification in the Gazette of India or a state gazette | Scheme notification | Yes |
| `guideline` | Official scheme guidelines / operational manual from the ministry or department | Guidelines PDF | Yes |
| `faq` | Official FAQ or help page from the scheme owner | FAQ on the scheme portal | No — `guidance` at most |
| `portal` | The official application portal's own text | National Scholarship Portal pages | Yes, for process facts (how to apply, documents, dates); `guidance` for eligibility |
| `govt_statement` | An official Government of India statement that is not the scheme's own guideline: a Cabinet decision or ministry release on pib.gov.in, a written reply in Parliament | "Cabinet approves …" on PIB | No, `guidance` at most. Used for recent changes before the revised guidelines are out, and for facts no guideline states. |
| `secondary` | Anything not published by the scheme owner: news, NGO explainers, aggregators | — | No. Used only to *find* official sources; never cited to users as the basis of a fact. |

**Preferred order:** gazette > guideline > portal > govt_statement > faq.

**Manual fetch.** Some official hosts block automated clients (pib.gov.in answers 403 to a bot User-Agent; some state sites disallow all bots in `robots.txt`). The re-check job respects that. Such a source carries `"fetch": "manual"` and a `fetch_note`; a reviewer opens it in a browser, saves the page and records its hash with `python ingest/recheck.py --hash-file <saved file>`. Where an official open-data source exists (e.g. data.gov.in, or myScheme where its terms permit), prefer it to scraping.

## 2. Ingestion and verification workflow

```
  ┌────────┐   ┌─────────┐   ┌───────────┐   ┌──────────────┐   ┌────────┐   ┌─────────┐
  │ Find   │──►│ Fetch   │──►│ Extract   │──►│ Review &     │──►│ Second │──►│ Publish │
  │ source │   │ snapshot│   │ chunk,    │   │ encode       │   │ check  │   │         │
  │        │   │ + hash  │   │ embed     │   │ (human)      │   │(human) │   │         │
  └────────┘   └─────────┘   └───────────┘   └──────────────┘   └────────┘   └─────────┘
```

1. **Find.** A reviewer adds the official URL(s) for a scheme. Secondary sources may point the way, but the record stores the official URL.
2. **Fetch.** `ingest/` fetches the page with Playwright (for JS-rendered portals) or plain HTTP, and PDFs with PyMuPDF. It:
   - checks `robots.txt` and the site's terms first, and skips anything disallowed;
   - rate-limits to at most 1 request per 5 seconds per host, with an identifying User-Agent and contact address;
   - stores a snapshot (`archived_copy`), `fetched_at` and `content_hash` (sha256 of the normalised text).
3. **Extract.** trafilatura (HTML) or PyMuPDF (PDF) extracts the text; scanned PDFs go through OCR and are flagged for closer review. Text is split into 300–500-token chunks along headings, each with a `locator` (page, heading path), then embedded.
4. **Review and encode** (reviewer UI). The reviewer reads the snapshot side-by-side with the structured form and:
   - writes the scheme summary and benefit text (plain language, grade 5);
   - encodes eligibility rules as json-logic, each linked to a clause (`source_locator`) and given a confidence label;
   - lists documents, deadlines and stacking/exclusion relations, each with a source;
   - adds test fixtures for every rule (see [RULES_ENGINE.md](RULES_ENGINE.md#8-testing));
   - sets `verified_at` and `verified_by` on the source.
5. **Second check.** A different reviewer approves. Nobody can approve their own work.
6. **Publish.** The scheme moves to `published`. Publishing is blocked unless the integrity rules in [DATA_MODEL.md](DATA_MODEL.md#5-integrity-rules) hold and the fixtures pass.

Translations of scheme content follow the same review rule: see [I18N_AND_VOICE.md](I18N_AND_VOICE.md#6-translation-review).

## 3. Confidence labels

Every rule and every assistant answer carries one of three labels.

| Label | Key | When | Shown as |
|---|---|---|---|
| **Confirmed rule** | `confirmed` | Taken directly from a gazette, guideline or official portal clause, verified by two reviewers, not stale | Green shield chip |
| **Official FAQ / guidance** | `guidance` | From an official FAQ or help text, or an official source whose wording is open to interpretation | Outlined document chip |
| **Unverified / may be outdated** | `uncertain` | Source has changed (`needs_review`), is past its re-verification interval, or the reviewer could only find partial support | Amber warning chip, plus the warning text |

An answer's label is the **weakest** label among the sources it cites.

## 4. Citation format

Every fact shown to a user, in the UI or in an assistant answer, carries a citation:

```json
{
  "source_id": "…",
  "title": "Indira Gandhi National Old Age Pension Scheme — Guidelines",
  "publisher": "Ministry of Rural Development",
  "url": "https://…",
  "locator": "p. 3, para 2.1",
  "published_date": "2014-…",
  "verified_at": "2026-10-12",
  "stale": false
}
```

**Display (compact):** `[1] Ministry of Rural Development · Guidelines, p. 3 · verified 12 Oct 2026`, with the link opening the official URL. Dates are formatted with `Intl.DateTimeFormat` in the user's language.

**Read aloud:** *"Source: Ministry of Rural Development guidelines. Last checked 12 October 2026."* URLs are never read aloud.

## 5. Re-check job

Runs daily (`.github/workflows/recheck.yml`). Scheme files are never edited by the job: any edit to one voids its reviews and demotes it (REVIEW.md §1). What it finds goes in `content/source-status.json`, which `tools/build-bundle.mjs` merges into the bundle.

1. For each source, respect `robots.txt`, fetch politely, normalise and hash. Sources marked `fetch: manual` are skipped.
2. **Same hash:** nothing to do; any earlier entry for the source is removed.
3. **Hash differs, but only numbers differ** from the saved snapshot (`counters_only`): portal running totals and "as on" dates. Not a change.
4. **Words differ** → status `changed`, and the issue opens. If anything in the scheme cites that source, the bundle gets `changed_at` on the source, both engines mark the scheme stale (RULES_ENGINE §9.5), its label becomes `uncertain`, and users see the "may be outdated" warning. The old content stays. This is what the brief calls `needs_review`; the scheme file's own `status` only changes when a reviewer acts. A source nothing cites gets an issue but no warning, because no fact rests on it.
5. **`robots.txt` forbids us** → status `manual_check`. It isn't fetched, isn't an error and shows no warning; a person checks it, and the re-verification interval still applies.
6. **Fetch failed** → in the issue, with no change to the status: a bad day at a government host says nothing about the page.
7. **Past interval:** if `now - verified_at > scheme.reverify_days` (default 180) for a hard rule's source, the job prints a `[   due]` line and the issue opens. The engines already mark such a scheme stale.
8. The workflow commits `source-status.json` and the rebuilt bundle, so a warning reaches the static site on the next deploy and the API on its next build.

A reviewer then either updates the scheme through REVIEW.md, or reads the change, finds no fact affected, and runs:

```bash
python ingest/recheck.py --dismiss pm-kisan/portal --by NAME --note "only the instalment news changed"
```

A dismissal records a hash of the page without its numbers, so it holds until the words change again. It verifies nothing: `verified_at` is still set only by `tools/publish.mjs`.

## 6. User reports

A "Report outdated info" button sits on every scheme page and every assistant answer. It opens a short form: what looks wrong (pick list), optional comment, and the current language. No login and no contact details are needed. The report creates a `review_task(kind = user_report)`. Three or more open reports on one scheme add the "may be outdated" warning until a reviewer resolves them.

## 7. MVP content plan

- About 30 schemes: central schemes plus one state (state to be chosen; see [ROADMAP.md](ROADMAP.md#open-questions)).
- Cover each persona in the brief: students (scholarships), elderly (pensions), farmers (income support, crop insurance), women and children, people with disabilities, housing and health.
- Each scheme needs: at least one gazette or guideline source, encoded hard rules with fixtures, documents with state-specific how-to-obtain, deadlines, and en + hi content reviewed by a human.
