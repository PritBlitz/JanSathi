# Handover — 2026-10-25 (end of session 2)

For the next AI session working in this repo. Read [CLAUDE.md](CLAUDE.md) and [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) first. The brief's §17 decisions log overrides the rest of the brief; [docs/ROADMAP.md](docs/ROADMAP.md) has the "Improvements over the brief" table.

## Maintainer instructions (standing)

- **Commits:** commit step by step, but the maintainer (PritBlitz) is the **only author**. No `Co-Authored-By` trailer and no Claude attribution in commit messages. **Never push**; the maintainer pushes.
- Build the product from the brief, and improve the idea where it helps. Explain each improvement in the docs.
- **All data must be real and verifiable.** No demo or placeholder data. Read the official source; record the clause (`locator`), `fetched_at` and `content_hash`. Never set `verified_at`: only a human reviewer does.
- Backend is **Spring Boot**. The hero word is the brand name in each language's script.
- Visuals follow [DESIGN.md](DESIGN.md). Update this file before the session limit runs out.

## Parallel work

`api/` (Spring Boot, Java engine in `api/src/main/java/org/jansathi/rules/`) is untracked and belongs to the backend session (`jansathi-20`, not running on 2026-10-25). Leave it alone; commit only your own paths (`git add <paths>`, never `-A`). The Java engine must pass the same fixtures in `rules/`, including the multi-scheme `next_question` cases (RULES_ENGINE §9.7).

## State at the end of this session

`npm run check` passes (content validation, copy lint, locale parity, bundle freshness, 312 unit tests); `npm run smoke` passes (headless Chrome: flows, console errors, hero fill 93–96% in all 10 locales). Lighthouse mobile on the English home page: performance 97, accessibility 100, best practices 100, SEO 100 (LCP 2.1 s, CLS 0). Re-run with `npm run serve` and `npx lighthouse@12.6.0 http://localhost:8000/ --form-factor=mobile`.

| Area | What exists |
|---|---|
| Engine | `web/js/rules.js` implements RULES_ENGINE §9 exactly. `rules/` has 233 fixture cases (44 generic + per scheme). |
| Content | **28 central schemes** in `content/schemes/` (list in `content/README.md`), 38 profile fields, 17 document types. Every rule has a clause locator; every source except `pm-jay/nha` has `fetched_at` + hash. All `status: draft`, `verified_at: null`. |
| Web | `web/` static PWA to DESIGN.md: hero, questionnaire (core fields then `next_question`, skip/back, exact number for straddles, voice answers), results with traces, scheme pages with numbered sources, checklist, search, ask sheet, read-aloud, offline service worker, delete-my-data. Auto **preview mode** (banner on every page) while `bundle.json` is empty. JS 17 KB / CSS 6 KB gzipped. |
| Locales | 10 files, 259 keys each; 9 machine-authored, tracked in `web/locales/review-status.json` (Tamil hero word flagged per DESIGN §6.3). |
| Tools | `validate-content`, `lint-copy` (10 languages, official-name allowlist, unit-tested), `check-locales`, `build-bundle` (`--check`), `serve`, `smoke-web`, `fetch-fonts`; `ingest/recheck.py` (robots.txt, 5 s/host, `--update`, `--hash-file`, LF output). |

### Commits this session

`110af54` engine · `461af7d` 10 schemes verified + recheck · `383403b` tools · `11a1fba` web PWA · `13e9969` docs · `7bb3df1` +8 schemes · `cf50d6a` recheck LF · `f5dafe7` +5 schemes · `8b1b7c7` +5 schemes · `1b69473` docs (plus handover updates).

## Source findings worth knowing (2026-10-25)

- **NSAP renamed** (Cabinet, 14 Oct 2026) to Pradhan Mantri Samajik Sahayata Yojana; disability pension moves to **40%, from birth, ₹375/₹625**, revised guidelines due by about mid-January 2027 → re-check `igndps` (has `reverify_days: 30`). Widow pension has no upper age (guidelines 2014 §2.3).
- **MGNREGA replaced by VB-G RAM G** from 1 Jul 2026 (125 days). Old job cards with e-KYC stay valid until Gramin Rozgar Guarantee Cards are issued.
- **PMAY-G** revised 10 exclusions (2024); portal is `pmayg.dord.gov.in` (`pmayg.nic.in` no longer resolves).
- **Guideline periods ended March 2026** for PMMVY (Mission Shakti), pre-matric SC, PM-YASASVI: noted in each file; confirm the current period.
- **PMUY** 25 lakh extension nearly exhausted on 23 Sep 2026 (`reverify_days: 60`).
- **Rules marked `uncertain` on purpose** (fail → "check with the office"): PMUY group (14-point declaration), JSY group (depends on LPS/HPS state; no `state` field yet).
- **Left out:** Stand-Up India (no extension past 2025 found), NMMSS (questionnaire can't capture its test/marks criteria).
- **Bot-blocked hosts** (sources marked `fetch: manual`, hash saved copies with `recheck.py --hash-file`): pib.gov.in (403), financialservices.gov.in (robots), labour.gov.in (403), mohua.gov.in (403), ssepd.odisha.gov.in (robots 403), nha.gov.in (robots + TLS revocation failure; not hashed).

## Next steps (in order)

1. **Human review** is the gate to going live: a reviewer checks each scheme against its sources, sets `verified_at`/`verified_by`, sets `status: published`, a second reviewer approves (content/README "Publishing a scheme"). Until then the app stays in preview mode.
2. **`state` field + one state's schemes** (brief MVP: central + one state; the maintainer must choose the state). A `state` field would also let JSY drop its `uncertain` rule. Needs 36 state/UT labels in 10 locales.
3. **Schedule the re-check** (GitHub Actions daily, coordinate with the backend session's `.github/workflows/ci.yml`) and wire `npm run check` into CI.
4. Content translation beyond en/hi (other languages fall back to English with a note); Phase 6 per the brief.
5. Not built from DESIGN: marquee, sticky storytelling, theme toggle, hero photos (abstract placeholder). Font first-load is ~150–175 KB for hi/bn/te/gu (budget 150 KB). Lighthouse is not in CI yet.
6. Tell the backend session about: multi-scheme fixtures, `govt_statement`, `fetch: manual`, the new fields and documents, `education_level` split (`up_to_class_8`, `class_9_10`).

## Open questions for the maintainer

MVP state; who the human reviewers are; sign-in vs anonymous; volunteer pilot partner; first reminder channel (Web Push or Telegram); whether the nav wordmark also changes script; Tamil hero word.

## Gotchas

- Bash heredocs with `₹` or Indic text fail here: write files with the Write tool or Node scripts (keep generator scripts in the scratchpad). Re-serialising `content/fields.json` with JSON.stringify expands its compact style; edit it by hand.
- Python: `python -I` for scripts; `pymupdf` needs the user site, so use `python -E -P`. No OCR: render scanned PDF pages to PNG and read them.
- XML comments in SVG must not contain `--` (it silently breaks the hero image).
- Scheme `missing_fields` follow **rule order**, not field order (§9.4) — write fixtures accordingly.
- `rm` with relative `../` paths is blocked; use absolute paths. Toolchains: Java 25, Node 24, Python 3.14, Docker, Chrome + Edge.
