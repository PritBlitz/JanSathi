# Handover — 2026-10-25 (session 3: full stack, React + Spring Boot)

For the next AI session working in this repo. Read [CLAUDE.md](CLAUDE.md) and [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) first. The brief's §17 decisions log overrides the rest of the brief; [docs/ROADMAP.md](docs/ROADMAP.md) has the "Improvements over the brief" table; [docs/FEASIBILITY.md](docs/FEASIBILITY.md) has the risks.

## Maintainer instructions (standing)

- **One session owns the whole project:** frontend, backend, content, tooling and docs. There is no separate backend session; change and test both sides together.
- **Commits:** commit step by step, but the maintainer (PritBlitz) is the **only author**. No `Co-Authored-By` trailer and no Claude attribution in commit messages. **Never push**; the maintainer pushes. Commit only when asked (nothing from session 3 is committed yet).
- Build the product from the brief, and improve the idea where it helps. Explain each improvement in the docs.
- **All data must be real and verifiable.** No demo or placeholder data, in the app or in tests. Read the official source; record the clause (`locator`), `fetched_at` and `content_hash`. Never set `verified_at`: only a human reviewer does.
- Backend is **Spring Boot**; frontend is **React** (2026-10-25). The hero word is the brand name in each language's script.
- The maintainer disliked the old coral palette and every design except the hero: the site was redesigned with Awwwards patterns (DESIGN.md v0.3, "Neel" palette). Keep the hero anatomy.
- Visuals follow [DESIGN.md](DESIGN.md). Update this file before the session limit runs out.

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
| Content | **28 central schemes**, 42 profile fields, 17 document types. All `status: draft`, `verified_at: null`, so the app is in **preview mode**. No `deadlines`, `relations` or `changes` recorded yet. |
| Locales | 10 files, 374 keys; 9 machine-authored; 32 keys are English placeholders outside `en` (`untranslated_keys` in `frontend/public/locales/review-status.json`). |

## Not built, and why

| Item | Why not yet |
|---|---|
| Reminders (Web Push / Telegram) | No scheme has a recorded deadline; reminders would have nothing true to say. Also: the first channel is an open maintainer question. |
| Stacking and conflicts | No `relations` in content yet. The data model supports them. |
| Volunteer portal, OCR (Phase 5) | Needs a threat model, a partner NGO/CSC and per-person sign-in (OIDC or passkeys) first; the brief marks them out of the MVP. |
| ML service (embeddings, IndicTrans2, server STT/TTS) | Needs a ≥ 4 GB machine; browser speech and keyword retrieval work today. pgvector retrieval comes with it. |
| State schemes | The app and API load `data/states/<code>.json` when the household's state has schemes, but the MVP state isn't chosen. |
| Nearby help map | No verified list of CSC locations to show yet. |
| Human review and native-speaker review | People, not code. These gate the launch. |

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

1. **Human review** is the gate to going live (docs/REVIEW.md): two independent approvals, then `tools/publish.mjs`. Until then the app stays in preview mode.
2. **MVP state** (maintainer's choice) and its schemes, written by someone who reads the state's language.
3. Push and fix whatever CI reports on its first run; add the `source-changed` label in GitHub.
4. Record `changes` (e.g. the NSAP Cabinet decision, with its PIB source and a quote checked against the snapshot) and `deadlines` where official sources give them; then reminders.
5. Grow the evaluation set to 100+ per language with held-out items; configure an LLM (`JANSATHI_LLM_*`) and measure the answer metrics in RAG_ASSISTANT §7.
6. Translate the 32 placeholder keys; native-speaker review of all 9 locales.

## Open questions for the maintainer

MVP state; who the human reviewers are; which LLM provider (and key) to use, if any; sign-in vs anonymous; volunteer pilot partner; first reminder channel (Web Push or Telegram); whether the nav wordmark also changes script; Tamil hero word.

## Gotchas

- Bash heredocs with `₹`, Indic text **or apostrophes** fail in this harness: write files with the Write tool or a Node script in the scratchpad.
- Re-serialising `content/fields.json` with JSON.stringify expands its compact style; edit it by hand.
- Python: `python -I` for scripts; `pymupdf` needs the user site, so use `python -E -P`. No OCR: render scanned PDF pages to PNG and read them.
- XML comments in SVG must not contain `--` (it silently breaks the hero image).
- Scheme `missing_fields` follow **rule order**, not field order (§9.4).
- React controlled inputs in the smoke test: set values with the native `HTMLInputElement` value setter and dispatch `input`, or React ignores them. Pages are lazy: wait for the page before clicking.
- Tomcat won't gzip a response with a strong ETag: use weak ETags (`W/"…"`).
- `Set.of(...)` throws on duplicate elements (the stop-word list in `TextMatch`).
- `rm` with relative `../` paths is blocked; use absolute paths. Toolchains: Java 25, Node 24, Python 3.14, Docker, Chrome + Edge.
