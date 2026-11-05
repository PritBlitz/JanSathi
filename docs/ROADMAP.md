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
- [x] First state: **Uttar Pradesh** (2026-11-04)
- [x] Spring Boot skeleton (`backend/`): Gradle, springdoc, Actuator, Flyway (H2 locally, PostgreSQL in production; tested against PostgreSQL 17 in Docker)
- [x] Python ML service skeleton (`ml/`) with `/v1/embed` (not yet run with a real model)
- [ ] Postgres + pgvector schema and migrations ([DATA_MODEL.md](DATA_MODEL.md)) (accounts, reports and reminders are in PostgreSQL; scheme content stays as code, see "Improvements"; vectors are held in memory until a model is chosen)
- [ ] Ingestion: fetch + snapshot + hash, extract, chunk, embed ([KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md))
- [x] Admin UI (`#/admin`): moderation queues, helpers, staff, source status. Publishing stays in `tools/publish.mjs`, because content is code
- [x] 49 published schemes (37 central, 12 Uttar Pradesh), en + hi, after two independent AI reviews (ai-r3, ai-r4 in the 2026-11-05 round). Earlier note: **28 central schemes written from official sources (2026-10-25) and all published after two independent AI reviews (19 on 2026-10-26, 9 on 2026-10-28; `ai-dual-review`). All 28 have Hindi content. A human review is still to come; no state chosen yet.**
- [x] Spring Boot read API: `/api/v1/schemes`, `/schemes/{slug}`, `/categories`, `/search` (from the published bundle; database pending)
- [x] Scheme detail page: summary, documents, apply link, sources with `verified_at` (a "When to apply" panel shows `deadlines` when recorded, otherwise the scheme's window type)
- [x] Copy lint and locale key-parity checks in CI (`.github/workflows/ci.yml`)

## Phase 2 — Rules engine and matching

- [x] json-logic rule format, band evaluation and three-state combination ([RULES_ENGINE.md](RULES_ENGINE.md)) in the JS engine
- [x] Rule fixtures for every seeded scheme, with Java/JS parity in CI (both engines run the 233 fixtures in `ci.yml`)
- [x] `/api/v1/rules/bundle` (ETag) and `POST /api/v1/match`, plus on-device matching from the same bundle
- [x] Adaptive questionnaire (tap / type / speak) driven by `next_question`
- [x] Results screen: likely / not eligible / need more info, with rule traces read aloud
- [x] Family mode (household questions once, member questions per person, results per member and for the whole household; on the device)
- [x] Stacking and conflict notes, with relations recorded for the scholarships from quoted clauses

## Phase 3 — RAG assistant (hi / en)

- [x] Retrieval (vector + keyword, fused) with thresholds: multilingual-e5-small measured on a held-out split, see `eval/README.md`. Vectors are in memory; pgvector is not needed at this size
- [x] Prompt contract, JSON answer schema and validation ([RAG_ASSISTANT.md](RAG_ASSISTANT.md))
- [x] "No official source" response (`POST /api/v1/ask` returns `not_found`; retrieval is keyword-based until pgvector)
- [x] Confidence labels and inline citations in the UI (ask sheet)
- [x] `LLMProvider` and `Embedder` interfaces (one OpenAI-compatible implementation covers Groq, Gemini and Ollama; `HttpEmbedder` talks to `ml/`)
- [ ] Evaluation set: 100+ items each in hi and en, run in CI with targets (103 en + 103 hi retrieval items run in CI, see `eval/README.md` for honest scores; still needs items from a different author; answer metrics need a model)

## Phase 4 — Checklist, reminders, re-checks

- [x] De-duplicated document checklist with have / don't have
- [x] State-specific "how to get it" for Uttar Pradesh (7 documents, quote-checked by the validator)
- [x] Deadline and checklist reminders: on the device and by Web Push, opt-in, payloadless (not yet tried against a real push service; Telegram not built; deadline reminders start when `deadlines` are recorded)
- [x] Daily source re-check job (`recheck.yml` opens an issue, writes `content/source-status.json`, and affected schemes show the "may be outdated" warning)
- [x] Re-verification interval tasks (schemes past `reverify_days` are reported by `recheck.py` and raised as an issue)
- [x] "Report outdated info" and the moderation queue (`report` table, admin API; the app posts to the API when it has one, else opens a GitHub issue)

## Phase 5 — Volunteer portal and OCR

- [x] Threat model for the volunteer portal (STRIDE in PRIVACY §4.6, with the items still open before a pilot)
- [x] Volunteer roles (registered by the admin; partner-lead verification pending)
- [x] Consent-scoped, time-limited sessions via short code, plus "ask for a helper" requests
- [x] Field masking and the user-visible audit log ([PRIVACY_AND_SECURITY.md](PRIVACY_AND_SECURITY.md)): `sensitive` consent scope; results run on the masked answers
- [ ] Pilot with a partner NGO or CSC (needs a partner)
- [x] OCR document-type and readability check: Tesseract in the browser (English and Hindi), so the photo never leaves the device

## Phase 6 — Scale out languages and channels

- [x] Machine translation of scheme content into 8 languages, labelled (`content/translations/`; Sarvam-Translate today, IndicTrans2 selectable with a Hugging Face token). Human review still to come
- [x] Server-side speech fallbacks: MMS text-to-speech in 10 languages, Whisper speech-to-text in en, hi, ta (others measured as unusable)
- [x] WhatsApp, SMS and Telegram reminders behind one interface (never run live: no provider accounts yet)
- [x] CSC and office map (OpenStreetMap + Leaflet): only after the person asks, location rounded to about 1 km, community data labelled as such, official CSC locator linked
- [x] Move the frontend to a framework: **React + Vite** (2026-10-25, same locale JSON)
- [x] Manual application tracking (My applications)
- [x] Anonymous rejection-reason insights (k ≥ 10; empty until real people share)
- [ ] More states (the pipeline is proven on Uttar Pradesh; each state needs its sources and reviews)

## Cross-cutting (every phase)

- [ ] Accessibility: axe on every page in the smoke test and Lighthouse in CI (done); a keyboard-only and real screen-reader pass by a person is still to do
- [x] Performance budget from [DESIGN.md](../DESIGN.md#10-performance-budget) enforced: `npm run check:budget` (sizes), `tools/lighthouse.mjs` (LCP, CLS). First-load JS is held at 88 KB, above the 60 KB target; see DESIGN §10
- [ ] Privacy review of each new feature against [PRIVACY_AND_SECURITY.md](PRIVACY_AND_SECURITY.md)

## Open questions

Decisions needed from the maintainer:

1. ~~Which state first?~~ Uttar Pradesh (2026-11-04).
2. ~~Sign-in in the MVP, or anonymous with an on-device profile?~~ Decided 2026-10-26: phone + OTP sign-in, optional; the app still works anonymously on the device.
3. Which NGO or CSC partners could pilot the volunteer portal?
4. ~~First reminder channel: Web Push or Telegram?~~ Built Web Push first on 2026-11-04 (needs no third-party account); change it if you prefer Telegram.
5. Should `recheck.yml` commit `content/source-status.json` to the default branch by itself?

## Decisions

| Date | Decision |
|---|---|
| 2026-10-24 | Backend is **Spring Boot** (Java 21). Python only for the internal ML service and ingestion. See [ARCHITECTURE.md](ARCHITECTURE.md). |
| 2026-10-24 | Hero display word is the brand name **localised per language** (JanSathi, जनसाथी, …). See [DESIGN.md](../DESIGN.md#6-hero-component). |
| 2026-10-26 | Sign-in by **phone + OTP**; every person gets **Saathi, an AI guide**, and registered **human helpers** (NGO/CSC) can help too; the AI is **Google Gemini** (OpenAI-compatible endpoint); scheme reviews are done by **independent AI reviewer agents** (REVIEW.md). |
| 2026-10-28 | Visual refresh (DESIGN.md v0.4): **light theme on every device** (dark only by choice), everything a size smaller, Plus Jakarta Sans and Bricolage Grotesque for Latin text, and a **real photo of people helping each other inside the letters** of the hero word (CC BY-SA, credited). |
| 2026-11-04 | **Full product, not the MVP** (see PROJECT_BRIEF §17): Uttar Pradesh first; Web Push and Telegram, then SMS and WhatsApp; Hugging Face models in `ml/`; machine-translated scheme content, labelled, outside the reviewed files. |
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
