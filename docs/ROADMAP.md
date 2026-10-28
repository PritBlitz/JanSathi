# Roadmap

Phases from the [project brief](PROJECT_BRIEF.md#12-roadmap). ✅ = done.

## Phase 0 — Multilingual voice-first frontend prototype ✅

- [x] PWA in `frontend/`: hero, category tiles, voice ask sheet, questionnaire (built 2026-10-25 as static HTML; moved to React + Vite and redesigned the same day)
- [x] 10-language UI (en, hi, bn, ta, te, mr, gu, kn, ml, pa) with `t('key')` and identical locale files, English fallback, saved choice
- [x] Text-to-speech (page, section, tile) and speech-to-text with typing fallback
- [x] Large touch targets, Noto fonts, dark mode, visible focus
- [ ] Native-speaker review of all nine non-English locales
- [x] Rebuild the hero and visuals to [DESIGN.md](../DESIGN.md) (abstract collage placeholder until licensed photos exist)

## Phase 1 — Knowledge base and read API

- [x] Decide backend framework: **Spring Boot** (2026-10-24)
- [ ] Decide the MVP state (see open questions)
- [x] Spring Boot skeleton (`backend/`): Gradle, springdoc, Actuator, Flyway (H2 locally, PostgreSQL in production; tested against PostgreSQL 17 in Docker)
- [ ] Python ML service skeleton (`ml/`) with `/v1/embed`
- [ ] Postgres + pgvector schema and migrations ([DATA_MODEL.md](DATA_MODEL.md))
- [ ] Ingestion: fetch + snapshot + hash, extract, chunk, embed ([KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md))
- [ ] Reviewer UI (minimal): source verification, scheme content, two-reviewer publish
- [ ] Seed ~30 verified schemes (central + one state) with en + hi content. **28 central schemes drafted and checked against official sources (2026-10-25); human review pending. MVP state not yet chosen.**
- [x] Spring Boot read API: `/api/v1/schemes`, `/schemes/{slug}`, `/categories`, `/search` (from the published bundle; database pending)
- [x] Scheme detail page: summary, documents, apply link, sources with `verified_at` (deadlines: none recorded yet)
- [x] Copy lint and locale key-parity checks in CI (`.github/workflows/ci.yml`)

## Phase 2 — Rules engine and matching

- [x] json-logic rule format, band evaluation and three-state combination ([RULES_ENGINE.md](RULES_ENGINE.md)) in the JS engine
- [x] Rule fixtures for every seeded scheme, with Java/JS parity in CI (both engines run the 233 fixtures in `ci.yml`)
- [x] `/api/v1/rules/bundle` (ETag) and `POST /api/v1/match`, plus on-device matching from the same bundle
- [x] Adaptive questionnaire (tap / type / speak) driven by `next_question`
- [x] Results screen: likely / not eligible / need more info, with rule traces read aloud
- [x] Family mode (household questions once, member questions per person, results per member and for the whole household; on the device)
- [ ] Stacking and conflict notes

## Phase 3 — RAG assistant (hi / en)

- [ ] Retrieval (pgvector + keyword, fused) with thresholds (keyword retrieval with a coverage gate built; pgvector pending)
- [x] Prompt contract, JSON answer schema and validation ([RAG_ASSISTANT.md](RAG_ASSISTANT.md))
- [x] "No official source" response (`POST /api/v1/ask` returns `not_found`; retrieval is keyword-based until pgvector)
- [x] Confidence labels and inline citations in the UI (ask sheet)
- [ ] `LLMProvider` and `Embedder` interfaces with at least two LLM providers (`LlmProvider` built; one OpenAI-compatible implementation covers Groq, Gemini and Ollama; `Embedder` pending)
- [ ] Evaluation set: 100+ items each in hi and en, run in CI with targets (45 en + 25 hi retrieval items run in CI; grow it with held-out items; answer metrics need a model)

## Phase 4 — Checklist, reminders, re-checks

- [x] De-duplicated document checklist with have / don't have
- [ ] State-specific "how to get it" for each document
- [ ] Deadline and checklist reminders: first channel (Web Push or Telegram — see open questions)
- [x] Daily source re-check job (`recheck.yml` opens an issue on change) and stale warnings in the app
- [ ] Re-verification interval tasks
- [x] "Report outdated info" and the moderation queue (`report` table, admin API; the app posts to the API when it has one, else opens a GitHub issue)

## Phase 5 — Volunteer portal and OCR

- [ ] Threat model for the volunteer portal (summary in PRIVACY §4.5; full STRIDE pending)
- [x] Volunteer roles (registered by the admin; partner-lead verification pending)
- [x] Consent-scoped, time-limited sessions via short code, plus "ask for a helper" requests
- [ ] Field masking and the user-visible audit log ([PRIVACY_AND_SECURITY.md](PRIVACY_AND_SECURITY.md)) (audit log built; per-field masking pending)
- [ ] Pilot with a partner NGO or CSC
- [ ] OCR document-type and readability check (delete after processing)

## Phase 6 — Scale out languages and channels

- [ ] IndicTrans2 content translation with human review flags
- [ ] Server-side STT (IndicWhisper) and TTS (Piper / Indic-TTS) fallbacks
- [ ] WhatsApp and/or SMS reminders
- [ ] CSC and office map (OpenStreetMap + Leaflet)
- [x] Move the frontend to a framework: **React + Vite** (2026-10-25, same locale JSON)
- [ ] Manual application tracking → anonymous rejection-reason insights
- [ ] More states

## Cross-cutting (every phase)

- [ ] Accessibility: Lighthouse + axe in CI, keyboard-only and screen-reader checks (Lighthouse accessibility 100 locally on 2026-10-25; not in CI yet)
- [ ] Performance budget from [DESIGN.md](../DESIGN.md#10-performance-budget)
- [ ] Privacy review of each new feature against [PRIVACY_AND_SECURITY.md](PRIVACY_AND_SECURITY.md)

## Open questions

Decisions needed from the maintainer:

1. Which state should the MVP cover first?
2. Sign-in in the MVP, or anonymous with an on-device profile? (The docs assume anonymous + optional sync.)
3. Which NGO or CSC partners could pilot the volunteer portal?
4. First reminder channel: Web Push or Telegram?

## Decisions

| Date | Decision |
|---|---|
| 2026-10-24 | Backend is **Spring Boot** (Java 21). Python only for the internal ML service and ingestion. See [ARCHITECTURE.md](ARCHITECTURE.md). |
| 2026-10-24 | Hero display word is the brand name **localised per language** (JanSathi, जनसाथी, …). See [DESIGN.md](../DESIGN.md#6-hero-component). |
| 2026-10-26 | Sign-in by **phone + OTP**; every person gets **Saathi, an AI guide**, and registered **human helpers** (NGO/CSC) can help too; the AI is **Google Gemini** (OpenAI-compatible endpoint); scheme reviews are done by **independent AI reviewer agents** (REVIEW.md). |
| 2026-10-25 | Frontend is **React** (Vite build), backend Spring Boot. Full visual redesign: "Neel" palette and Awwwards-pattern sections; the hero anatomy is kept. See [DESIGN.md](../DESIGN.md) and [FEASIBILITY.md](FEASIBILITY.md). |

## Improvements over the brief

Changes made while building, and why. The brief still describes the goals; these describe how we got there.

| Improvement | Why |
|---|---|
| **Content as code** (`content/*.json`, reviewed in pull requests, with the second reviewer as the PR approver) | Works before the reviewer UI and database exist, gives a full history of every rule change, and is the same shape the API imports. |
| **Static offline bundle** (`frontend/public/data/bundle.json`) with the rules engine on the device | Matching works offline and on slow networks, and the profile never leaves the phone (principle 7). The API serves the same bundle. |
| **Custom three-valued evaluator** instead of a stock json-logic library | Stock json-logic has no "unknown". Answers like "don't know" and age bands need Kleene logic and band corners to give honest "need more information" results. See RULES_ENGINE §9. |
| **Spring Boot 4.1** rather than 3.x | Open-source support for 3.x has ended. |
| **`govt_statement` source type** (PIB Cabinet releases, ministry replies in Parliament) | Schemes change by Cabinet decision months before revised guidelines appear (e.g. the NSAP disability pension, 14 Oct 2026). Users get the current rule, labelled `guidance`, with the source shown. |
| **Manual-fetch sources** | Some official hosts block bots. The re-check job respects robots.txt and records such sources for a reviewer to hash by hand. |
| **PM-KISAN government-job question includes the Group D exception** | The guidelines exclude only jobs above Group D / Class IV / MTS; a plain "government job?" question would wrongly exclude eligible families. |
| **Exact-number follow-up for straddling bands** | When a limit falls inside the chosen band (e.g. income 5–8 lakh against "below ₹8 lakh"), the app asks for the exact figure instead of guessing. |
