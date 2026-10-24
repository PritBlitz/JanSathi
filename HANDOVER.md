# Handover — 2026-10-24

For the next AI session working in this repo. Read [CLAUDE.md](CLAUDE.md) and [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) first. The brief's §17 decisions log overrides the rest of the brief.

## Maintainer instructions (standing)

- **Commits:** commit step by step, but the maintainer (PritBlitz) is the **only author**. No `Co-Authored-By` trailer and no Claude attribution in commit messages. **Never push**; the maintainer pushes.
- Build the product from the brief, and improve the idea where it helps. Explain each improvement in the docs.
- Backend is **Spring Boot**. The hero word is the brand name in each language's script (JanSathi, जनसाथी, …).
- Visuals follow [DESIGN.md](DESIGN.md): the Voices United-style hero, Awwwards-inspired patterns, and the accessibility and performance limits.

## Two sessions are working in parallel

Another session (`jansathi-20`) is working in the same folder. Agreed split, so we don't overwrite each other:

| Owner | Paths |
|---|---|
| **This (frontend) session's successor** | `content/`, `tools/`, `web/`, `rules/` fixtures, `package.json`, `docs/RULES_ENGINE.md` §9, frontend docs |
| **`jansathi-20`** | `api/` (Spring Boot 4.1.2, Java 25, Gradle, package `org.jansathi`), the Java rules engine, `.github/workflows/ci.yml`, backend docs |

Commit only your own paths (`git add <your paths>`, never `git add .` or `-A`). `api/` is untracked and belongs to `jansathi-20`; leave it alone. Run `ListAgents` to check whether `jansathi-20` is still active.

## Done (committed)

1. `aedd6f2` Docs: brief, README, CLAUDE.md, DESIGN.md, and all `docs/*.md`.
2. `abc19c5` Repo tooling: `.gitattributes` (LF), `.editorconfig`, `.gitignore`.
3. `1120f5c` `content/`: `fields.json` (profile fields, bands, answer options), `documents.json` (14 document types with how-to-get-it guidance in en and hi), and 10 central schemes in `schemes/*.json` (en + hi content, sources, rules, documents).
   - Every scheme is `status: draft` with `verified_at: null`, because no human has reviewed them. Only a human sets `verified_at`.
   - Source URLs and hashes fetched by `jansathi-20` are recorded for PM-KISAN (guidelines PDF + FAQ), SSY, the NSAP pensions (nsap.dord.gov.in) and post-matric SC.
   - NSAP and post-matric SC rules are `guidance`: their criteria come from secondary sources only. The post-matric SC income limit may rise to ₹4.5 lakh (2026 news).
4. **Not yet committed:** `docs/RULES_ENGINE.md` §9, the exact engine contract (three-valued logic, band corners, straddles, stale/label, `next_question`, fixture and API JSON shapes). Commit it together with this file. Both engines must follow §9.

## Next steps (in order)

1. **`rules/` fixtures + JS engine.** Write `web/js/rules.js`: an ES module with no DOM, exporting `evaluateRule`, `evaluateScheme`, `match(bundle, profile, {skipped, as_of})`, implementing §9 exactly. Add `rules/_engine.cases.json` (generic semantics: Kleene `or`, straddle, uncertain-fail, stale) and `rules/<slug>.cases.json` per scheme, with boundary cases (59/60/61, 249999/250000/250001, a straddling band, missing fields). Add `tools/test-rules.mjs` (`node --test`). Then tell `jansathi-20` so the Java engine can run the same fixtures.
2. **`tools/`** (plus a root `package.json` with scripts; no dependencies if possible):
   - `build-bundle.mjs`: content → `web/data/bundle.json`. Drafts are left out unless `--include-drafts`, which writes `web/data/bundle.preview.json`.
   - `lint-copy.mjs`: banned phrases in en, hi and the other languages, checked across `web/locales`, `content`, and prompts.
   - `check-locales.mjs`: identical key sets across all 10 locales.
   - `validate-content.mjs`: every rule, document and deadline references an existing source; `confirmed` rules don't rest on `faq` or `secondary` sources; only allowed operators; only fields from `fields.json`.
3. **`web/` frontend.** It doesn't exist yet: the brief's Phase 0 prototype was never in the repo. Build it to DESIGN.md:
   - `index.html`, `css/styles.css` (tokens from DESIGN §3–5);
   - `js/i18n.js` (LANGS, `data-i18n` / `data-i18n-attr`, English fallback, `localStorage` wrapped in try/catch, `<html lang>`);
   - `js/speech.js` (speak / stop / listen / hasVoice, with a warning when there's no voice);
   - `js/app.js`: hash router with these views:
     - home: knockout hero word from `hero.display`, CTAs, "what's open now" rail, category tiles;
     - questionnaire: core fields `age, gender, area, occupation`, then `next_question`, with a skip option and exact-number input for straddles;
     - results: three states with rule traces and read-aloud;
     - scheme detail: content, documents, sources with `verified_at`, stale/draft warning, "report outdated info";
     - checklist: de-duplicated, have / don't have, how to get it;
     - search: local keyword search for the ask box, since the assistant is Phase 3; say so honestly.
   - Locale files: `locales/<10 codes>.json` with keys `q.<field>`, `opt.<field>.<value>`, `opt.bool.yes|no|dont_know`, `result.*`, `trust.*`, `hero.*`, `nav.*`. Non-English strings are machine-authored; record them in `locales/review-status.json`.
   - Hero collage: a placeholder abstract SVG fill until licensed photos exist (`web/img/CREDITS.md`).
   - PWA: `manifest.webmanifest` + `sw.js` (cache the shell and the bundle).
   - In preview mode, draft schemes show a "Draft, not yet verified" banner.
   - The profile stays in `localStorage` only, with a "Delete my data" button.
4. **`ingest/recheck.py`**: fetch sources (respect robots.txt, 1 request every 5 s per host, User-Agent `JanSathi-bot (+repo URL)`; **never put the maintainer's email in it**), normalise, sha256, report changes, and `--update <slug>` to write `fetched_at` / `content_hash`. Coordinate with `jansathi-20` before CI runs it.
5. **Docs:** update README status, the ROADMAP checkboxes, and CLAUDE.md run/test commands as features land. Record these improvements over the brief:
   - content-as-code, with PR review as the second check;
   - a static offline bundle;
   - a custom three-valued evaluator;
   - Spring Boot 4.1 (3.x community support has ended);
   - the PM-KISAN government-job question written to include the Group D exception.

## Open questions for the maintainer

MVP state; sign-in vs anonymous; volunteer pilot partner; first reminder channel (Web Push or Telegram); whether the nav wordmark also changes script.

## Gotchas

- Bash heredocs containing `₹` or Devanagari failed in this environment. Write JSON with the Write tool, or with `node -e` and `fs`.
- `rm` with relative `../` paths is blocked by a safety check; use absolute paths.
- Toolchains available: Java 25, Node 24, Python 3.14, Docker (running). There is no global Gradle; use the wrapper in `api/`.
