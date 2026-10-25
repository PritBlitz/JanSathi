# Handover — 2026-10-25 (session 2, in progress)

For the next AI session working in this repo. Read [CLAUDE.md](CLAUDE.md) and [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) first. The brief's §17 decisions log overrides the rest of the brief.

## Maintainer instructions (standing)

- **Commits:** commit step by step, but the maintainer (PritBlitz) is the **only author**. No `Co-Authored-By` trailer and no Claude attribution in commit messages. **Never push**; the maintainer pushes.
- Build the product from the brief, and improve the idea where it helps. Explain each improvement in the docs.
- **All data must be real and verifiable.** No demo or placeholder data. Use official sources from the internet; record the clause (`locator`), `fetched_at` and `content_hash`. Never set `verified_at` yourself: only a human reviewer does.
- Backend is **Spring Boot**. The hero word is the brand name in each language's script (JanSathi, जनसाथी, …).
- Visuals follow [DESIGN.md](DESIGN.md).
- Update this file before the session limit runs out.

## Parallel work

`api/` (Spring Boot, Java engine in `api/src/main/java/org/jansathi/rules/`) is untracked and belongs to the backend session (`jansathi-20`, not running on 2026-10-25). Leave it alone; commit only your own paths (`git add <paths>`, never `-A`). The Java engine must pass the same fixtures in `rules/`.

## Done this session (committed)

1. `110af54` **JS rules engine** `web/js/rules.js` (§9 exactly), `rules/_engine.cases.json` (44 cases incl. multi-scheme `next_question`; format added to RULES_ENGINE §9.7), `tools/test-rules.mjs`, root `package.json` (no dependencies).
2. `461af7d` **All 10 schemes checked against official sources** (see "Source findings" below), per-scheme fixtures (96 cases), `ingest/recheck.py`, new `doc_type: govt_statement`, manual-fetch sources, 7 new profile fields, `job_card` document.
3. `383403b` **Tools:** `validate-content`, `lint-copy` (10 languages + official-name allowlist), `check-locales`, `build-bundle` (+ `--check`), `serve`. `npm run check` runs all of them; `web/data/bundle.json` is empty because nothing is published yet, `bundle.preview.json` has the 10 drafts.

## Source findings (2026-10-25)

- **NSAP** renamed on 14 Oct 2026 to Pradhan Mantri Samajik Sahayata Yojana (PIB PRID 2323264). Old age: 60+, BPL, ₹200 (₹500 at 80+). Widow: 40+, BPL, ₹300 (₹500 at 80+); **no upper age**. Disability: **40%, from birth, ₹375 / ₹625**; revised guidelines due within three months (by mid-Jan 2027), so `igndps` has `reverify_days: 30`. Guidelines Oct 2014 are hosted on the Odisha SSEPD site (robots blocks bots → manual).
- **PM-KISAN** guidelines unchanged (hash matches); continued 2026-27 to 2030-31 at ₹6,000 (PIB 2292460).
- **PM-JAY** 70+ for all incomes (PIB 2053883). `nha.gov.in` disallows bots and its certificate failed revocation checks → `nha` source has no hash yet.
- **PMAY-G** revised 10 exclusions (PIB 2080091), ₹1.20 / 1.30 lakh (PIB 2043921). Portal moved to `pmayg.dord.gov.in`.
- **PMFBY** PIB factsheet 29 Aug 2026: tenants and sharecroppers covered, voluntary, 25 states/UTs in Kharif 2026.
- **PMMVY** Mission Shakti guidelines (2021-22 to 2025-26): income **below** ₹8 lakh, plus e-Shram, job card and other groups. Reviewer must confirm the scheme period after March 2026.
- **MGNREGA replaced by VB-G RAM G Act from 1 Jul 2026** (125 days; PIB 2279446 and FAQ 158511). Good candidate for a new scheme file.
- **SSY** NSI page (portal → `guidance`). **Post-matric SC** guidelines (scanned PDF; read page images): §5.2, §5.3 ₹2.5 lakh unchanged. Reports of a ₹4.5 lakh limit are proposals only.
- pib.gov.in answers 403 to the bot UA: those sources are `fetch: manual`; hash saved copies with `python ingest/recheck.py --hash-file`.

## Next steps (in order)

1. **`web/` frontend** (in progress next): index.html, css (DESIGN tokens), i18n.js, speech.js, app.js (home, questionnaire, results, scheme detail, checklist, search), 10 locale files + `review-status.json`, PWA manifest + sw.js, preview mode for drafts, delete-my-data.
2. More schemes toward ~30, each from official sources (VB-G RAM G, NFBS/PM Parivar Labh, Atal Pension, PMJJBY, PMSBY, PM-SYM, PM Ujjwala, Pre-matric SC, NMMSS, PM SVANidhi, Stand-Up India, PM Vishwakarma, JSY, …).
3. Docs: README status, ROADMAP checkboxes (Phase 0 is marked done but `web/` did not exist), CLAUDE.md run/test commands.
4. Tell the backend session: fixtures, `govt_statement`, `fetch: manual`, new fields.

## Open questions for the maintainer

MVP state; sign-in vs anonymous; volunteer pilot partner; first reminder channel (Web Push or Telegram); whether the nav wordmark also changes script; who will be the human reviewers that set `verified_at`.

## Gotchas

- Bash heredocs with `₹` or Devanagari fail here; write JSON with the Write tool or Node scripts. Re-serialising `content/fields.json` with JSON.stringify expands its compact style; edit it by hand.
- Python: use `python -I` for scripts; PDF text with `pymupdf` needs the user site, so use `python -E -P`. No OCR is installed: render scanned pages to PNG and read them.
- `rm` with relative `../` paths is blocked; use absolute paths.
- Toolchains: Java 25, Node 24, Python 3.14, Docker. No global Gradle; use the wrapper in `api/`.
