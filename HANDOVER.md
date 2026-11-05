# Handover — updated 2026-11-05 (session 5: full product; see "Full-product round" first)

For the next AI session working in this repo. Read [CLAUDE.md](CLAUDE.md) and [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) first. The brief's §17 decisions log overrides the rest of the brief; [docs/ROADMAP.md](docs/ROADMAP.md) has the "Improvements over the brief" table; [docs/FEASIBILITY.md](docs/FEASIBILITY.md) has the risks.

## Maintainer instructions (standing)

- **One session owns the whole project:** frontend, backend, content, tooling and docs. There is no separate backend session; change and test both sides together.
- **Commits:** commit step by step, but the maintainer (PritBlitz) is the **only author**. No `Co-Authored-By` trailer and no Claude attribution in commit messages. **Never push**; the maintainer pushes. Commit only when asked.
- Build the product from the brief, and improve the idea where it helps. Explain each improvement in the docs.
- **All data must be real and verifiable.** No demo or placeholder data, in the app or in tests. Read the official source; record the clause (`locator`), `fetched_at` and `content_hash`. Never set `verified_at` by hand: only `tools/publish.mjs` does, after two independent approvals (AI reviewer agents or people, docs/REVIEW.md).
- Backend is **Spring Boot**; frontend is **React** (2026-10-25). The hero word is the brand name in each language's script.
- The maintainer disliked the old coral palette and every design except the hero: the site was redesigned with Awwwards patterns (DESIGN.md v0.3, "Neel" palette). Keep the hero anatomy.
- 2026-10-28 (DESIGN.md v0.4): the maintainer wants **light colours** (light on every device, dark only by the theme button), **smaller** type, spacing and controls, **good fonts** (Plus Jakarta Sans text, Bricolage Grotesque headings), and **photos of people inside the letters** of the hero word (knockout), not a photo behind the word. They liked the knockout hero; keep it.
- Visuals follow [DESIGN.md](DESIGN.md). Update this file before the session limit runs out.

## Full-product round (2026-11-05)

The maintainer asked for the full product and delegated the open decisions (PROJECT_BRIEF §17). Nothing is committed.

**Content.** 21 new schemes were authored, reviewed by two fresh agents (ai-r3, ai-r4) who recorded 67 blocker findings between them, many the same problem, corrected, and re-reviewed. All 49 schemes are published. Where a guideline period ended in March 2026 and no continuation order was found (adip, pmegp, pmkvy, SC and OBC scholarships) rules were lowered to `guidance` and the text says so. `docs/REVIEW.md` gained `counters_only`.

Known soft spots, all disclosed in the content or review records: the UP pension may rise to ₹1,500 (no order found by 2026-11-05: re-check); up-old-age-pension has no residence clause in any source read; up-post-matric-general's income limit is unverified and the rule is `uncertain`; up-shadi-anudan includes ST on a 2016 order while the current page doesn't; relation notes on pm-usp and the PwD scholarships say "National Scholarship Portal" even for UP pairs (minor). Four snapshots hold marked hand transcriptions of scans: don't run `recheck --snapshot` on those sources.

**Never run live (no accounts or keys):** MSG91, Twilio, Telegram, WhatsApp, real browser push, Sentry delivery, Caddy with real TLS, backup and restore, an LLM for Saathi, IndicTrans2. `docs/OPERATIONS.md` §12 lists what could differ.

**Models (`ml/README.md`, `eval/README.md`).** Held-out retrieval: en 38/40, hi 37/40, refusal 100%; keywords alone find nothing in other scripts, fused ta 28/38, bn 28/34 on machine-translated questions. Rerun the threshold sweep after adding schemes (`JANSATHI_EVAL_SWEEP=1`). Speech-to-text is offered only in en, hi, ta; measured on synthetic audio only. In the round trip the spoken amount came back wrong, so never treat transcribed numbers as answers.

**Licences to decide before any commercial use:** MMS voices (CC-BY-NC, in the live speech path); Sarvam-Translate (GPL-3.0, third-party quantisation; generated text is what ships).

**Translation** runs through `tools/translate-content.mjs`; the build serves only fields whose English hash still matches, and the bundle lists which languages exist, so a language in progress falls back to English. After editing a scheme, rerun it, then `npm run build`. On 2026-11-05 it was partial: Bengali, Tamil, Gujarati and Malayalam started, Telugu, Marathi, Kannada and Punjabi not. On this CPU expect about 10 hours for the rest. Run llama with the model on a Docker volume (a Windows bind mount is about 10x slower) and with `--cache-ram 0` (the 8 GB default got it killed for memory).

**Certificates:** `pmsvanidhi.mohua.gov.in` and `scholarships.gov.in` report a revoked certificate from this machine (it may be local). So pm-svanidhi/portal stays "changed" with its warning, and the NSP copies behind pm-usp and the scholarship notes need comparing with a copy from a trusted connection. The re-check uses urllib, which does not check revocation.

**Robots lapses in this round, for the record:** the author fetched from msme.up.gov.in, cdn.s3waas.gov.in and pfrda.org.in before reading their robots.txt (they disallow bots); it stopped and nothing cites them. Reviewers fetched a few hosts with unreadable robots.txt.

## Since session 4 (2026-11-04)

Checked the project against the brief, then built what needed no one else. Nothing committed.

- **Built:** My applications (§6.7), reminders on the device and by Web Push (§6.5), photo check by OCR in the browser (§6.4), nearby help with OpenStreetMap and Leaflet (§6.8), "When to apply" and relation notes, helper field masking with a `sensitive` scope (§6.6), source-change and re-verification warnings without editing scheme files, STRIDE threat model, `Embedder` and `ml/` skeleton, 103 + 103 evaluation items, axe, Lighthouse and size budget in CI.
- **Fixed:** `Permissions-Policy` blocked the microphone on the jar, so voice input could not work when deployed. Saathi for helpers saw the full profile without the `profile` scope. Four stale backend tests. The footer word failed contrast audits (now drawn by CSS).
- **Locales:** all 9 languages are now fully translated (about 208 keys each were English placeholders; `untranslated_keys` are empty). All machine-authored: native-speaker review is still needed.
- **`deadlines` and `relations` are still empty in every scheme.** The UI is ready. Adding them changes the scheme's content version, which sends it back to `needs_review` until two new reviews approve it. The saved snapshots hold no current dates, so this needs new source reading.
- **Sources needing a person (2026-11-04):** pm-svanidhi/portal changed and shows the warning; 8 sources for pmmvy, post-matric-obc, post-matric-sc and pre-matric-sc are now disallowed by robots.txt and must be checked by hand.

The "State at the end of session 3" and "Not built" sections below are history where this section disagrees.

## Since session 3 (2026-10-26 to 2026-10-28)

- **Accounts, Saathi, helper portal** (commits ee14c34, d3f589b, 4ca4af4, docs in 579877d): phone + OTP sign-in (phone stored only as a keyed hash and last 4 digits), encrypted household sync, Saathi the AI guide (agent loop over deterministic tools; the rules engine decides), share-by-code with consent scopes and an audit log, helper portal, admin API for helpers. Needs `JANSATHI_SECRET`; see `.env.example`.
- **All 28 schemes published** after two independent AI reviews (`ai-dual-review`): 19 on 2026-10-26, 9 on 2026-10-28. The app says "Checked by AI reviewers" and that no person has checked them yet; it is no longer in preview mode. Open minor notes from the reviewers are in commit e5cfec8.
- **Visual refresh** (DESIGN.md v0.4): light palette with lavender bands, smaller scale, 44 px minimum touch targets, new Latin fonts, and the hero word filled with a CC BY-SA photo of volunteers at a Delhi community kitchen (`frontend/public/img/CREDITS.md`; credit line under the word).
- The smoke test now deletes its Chrome profile when it ends. Earlier runs left `jansathi-chrome-*` folders (about 50 MB each) in the system temp folder.

## State at the end of session 3

All green, measured on 2026-10-25:

| Check | Result |
|---|---|
| `npm run check` | content, copy lint (10 languages + the assistant prompt), locale parity (374 keys), bundle freshness, 312 JS tests |
| `npm run test:backend` | 326 tests: 291 engine fixture tests, 14 API, 10 answer validator, 7 assistant pipeline, 2 LLM wire format, retrieval evaluation, context |
| `npm run smoke` | headless Chrome on the built PWA: every flow, ask sheet, theme, marquee, family mode, office slip; no console errors; hero fill 93–96% in all 10 locales. Also passes with `--lang ta`, `--lang ml --scheme light`, and `--url http://localhost:8080/` against the jar and the Docker image |
| Lighthouse (mobile, jar on :8080) | performance 97, accessibility 100, best practices 100, SEO 100; LCP 1.7 s, TBT 170 ms, CLS 0 |
| Docker | `docker compose up --build` runs on PostgreSQL 17: Flyway V1 applied, reports stored and redacted, admin API works |

| Area | What exists |
|---|---|
| Engine | `frontend/src/lib/rules.js` and `backend/.../rules/RulesEngine.java` implement RULES_ENGINE §9 exactly; both pass the 233 fixtures in `rules/`. |
| API | `/api/v1`: schemes, categories, search, match, rules/bundle (weak ETag, gzip), checklist, ask, reports, admin/reports (bearer token). OpenAPI at `/v3/api-docs`. Problem+json errors, security headers, CORS for the Vite ports. Flyway migrations; H2 file DB locally, PostgreSQL in production. The jar also serves the PWA (`npm run build:jar`). |
| Assistant | `POST /ask`: redact personal numbers → keyword retrieval with IDF, whole words, a 50% keyword-coverage gate → optional LLM explanation (`LlmProvider`, OpenAI-compatible: Groq, Ollama, Gemini) → validation (JSON, citations ⊆ retrieved, inline [pN], banned phrases from `tools/banned-phrases.json`, 180-word cap) → one retry → `not_found`. Model failures degrade to cited passages. Off by default (`JANSATHI_LLM_PROVIDER=none`). |
| Evaluation | `eval/rag/{en,hi}.jsonl` (70 items, 15 unanswerable) run by `RetrievalEvalTest`: hit 100%, correct refusal 100%. **Tuned on this same set**, so it overstates quality: add held-out items written by someone else. |
| Web | React 19 PWA to DESIGN.md v0.3. Home (hero, marquee, manifesto, stats, sticky how-it-works, category index, trust band, CTA, footer), questionnaire, results, scheme detail, schemes, checklist with "ready to apply", office slip (print/share), family mode, "what changed" (hidden until content has `changes`), search, ask sheet (shows the LLM answer when the API has one), privacy. Nav measures itself and switches to the menu when a language's labels don't fit. Lazy-loaded pages; service worker precaches all hashed assets. Main JS 81 KB gzipped. |
| CI | `.github/workflows/ci.yml` (check, smoke in en and ta, backend tests, Docker build on main) and `recheck.yml` (daily source re-check; opens an issue when a source changes). Not yet run on GitHub: the first push will tell. |
| Content | **28 central schemes**, 42 profile fields, 17 document types. (Session 3: all drafts. Since 2026-10-28 all published, see above.) No `deadlines` or `relations` recorded yet. |
| Locales | 10 files, 374 keys; 9 machine-authored; 32 keys are English placeholders outside `en` (`untranslated_keys` in `frontend/public/locales/review-status.json`). |

## Not built, and why

| Item | Why not yet |
|---|---|
| Reminders (Web Push / Telegram) | No scheme has a recorded deadline; reminders would have nothing true to say. Also: the first channel is an open maintainer question. |
| Stacking and conflicts | No `relations` in content yet. The data model supports them. |
| OCR (Phase 5) | Needs a threat model first; the brief marks it out of the MVP. (The helper portal was built on 2026-10-26; a pilot partner is still needed.) |
| ML service (embeddings, IndicTrans2, server STT/TTS) | Needs a ≥ 4 GB machine; browser speech and keyword retrieval work today. pgvector retrieval comes with it. |
| State schemes | The app and API load `data/states/<code>.json` when the household's state has schemes, but the MVP state isn't chosen. |
| Nearby help map | No verified list of CSC locations to show yet. |
| Human review and native-speaker review | People, not code. Schemes are AI-reviewed; a person's review and native-speaker review of the 9 locales are still to come. |

## Source findings worth knowing (2026-10-25)

- **NSAP renamed** (Cabinet, 14 Oct 2026) to Pradhan Mantri Samajik Sahayata Yojana; disability pension moves to **40%, from birth, ₹375/₹625**, revised guidelines due by about mid-January 2027 → re-check `igndps` (has `reverify_days: 30`). Widow pension has no upper age (guidelines 2014 §2.3).
- **MGNREGA replaced by VB-G RAM G** from 1 Jul 2026 (125 days). Old job cards with e-KYC stay valid until Gramin Rozgar Guarantee Cards are issued.
- **PMAY-G** revised 10 exclusions (2024); portal is `pmayg.dord.gov.in` (`pmayg.nic.in` no longer resolves).
- **Guideline periods ended March 2026** for PMMVY (Mission Shakti), pre-matric SC, PM-YASASVI: noted in each file; confirm the current period.
- **PMUY** 25 lakh extension nearly exhausted on 23 Sep 2026 (`reverify_days: 60`).
- **Rules marked `uncertain` on purpose** (fail → "check with the office"): PMUY group (14-point declaration), JSY group (depends on LPS/HPS state).
- **Left out:** Stand-Up India (no extension past 2025 found), NMMSS (questionnaire can't capture its test/marks criteria).
- **Bot-blocked hosts** (sources marked `fetch: manual`, hash saved copies with `recheck.py --hash-file`): pib.gov.in (403), financialservices.gov.in (robots), labour.gov.in (403), mohua.gov.in (403), ssepd.odisha.gov.in (robots 403), nha.gov.in (robots + TLS revocation failure; not hashed).

## Next steps (in order)

1. **Human review** on top of the AI reviews (docs/REVIEW.md): a person's approval upgrades a scheme to "Verified by a reviewer". Address the reviewers' open minor notes (commit e5cfec8).
2. **MVP state** (maintainer's choice) and its schemes, written by someone who reads the state's language.
3. Push and fix whatever CI reports on its first run; add the `source-changed` label in GitHub.
4. Record `changes` (e.g. the NSAP Cabinet decision, with its PIB source and a quote checked against the snapshot) and `deadlines` where official sources give them; then reminders.
5. Grow the evaluation set to 100+ per language with held-out items; configure an LLM (`JANSATHI_LLM_*`) and measure the answer metrics in RAG_ASSISTANT §7.
6. Translate the 32 placeholder keys; native-speaker review of all 9 locales.

## Open questions for the maintainer

MVP state; who the human reviewers are; volunteer pilot partner; which SMS provider to use for sign-in codes; first reminder channel (Web Push or Telegram); whether the nav wordmark also changes script; Tamil hero word.

## Gotchas

- Bash heredocs with `₹`, Indic text **or apostrophes** fail in this harness: write files with the Write tool or a Node script in the scratchpad.
- Re-serialising `content/fields.json` with JSON.stringify expands its compact style; edit it by hand.
- Python: `python -I` for scripts; `pymupdf` needs the user site, so use `python -E -P`. No OCR: render scanned PDF pages to PNG and read them.
- XML comments in SVG must not contain `--` (it silently breaks the hero image).
- Scheme `missing_fields` follow **rule order**, not field order (§9.4).
- `npm run start:backend` fails with "port 8080 already in use" if an old `java -jar build/libs/api-*.jar` (e.g. left over from `npm run smoke -- --url http://localhost:8080/`) is still running. Find it with `netstat -ano | findstr :8080` and stop it first; Vite's `/api` proxy otherwise talks to that stale build.
- React controlled inputs in the smoke test: set values with the native `HTMLInputElement` value setter and dispatch `input`, or React ignores them. Pages are lazy: wait for the page before clicking.
- Tomcat won't gzip a response with a strong ETag: use weak ETags (`W/"…"`).
- `Set.of(...)` throws on duplicate elements (the stop-word list in `TextMatch`).
- `rm` with relative `../` paths is blocked; use absolute paths. Toolchains: Java 25, Node 24, Python 3.14, Docker, Chrome + Edge.
