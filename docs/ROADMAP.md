# Roadmap

Phases from the [project brief](PROJECT_BRIEF.md#12-roadmap). ✅ = done.

## Phase 0 — Multilingual voice-first frontend prototype ✅

- [x] Static, no-build PWA page in `web/`: hero, category tiles, voice ask box, quick check
- [x] 10-language UI (en, hi, bn, ta, te, mr, gu, kn, ml, pa) with `data-i18n` / `data-i18n-attr`, English fallback, saved choice
- [x] Text-to-speech (page, section, tile) and speech-to-text with typing fallback
- [x] Large touch targets, Noto fonts, dark mode, visible focus
- [ ] Native-speaker review of all nine non-English locales
- [ ] Rebuild the hero and visuals to [DESIGN.md](../DESIGN.md)

## Phase 1 — Knowledge base and read API

- [x] Decide backend framework: **Spring Boot** (2026-10-24)
- [ ] Decide the MVP state (see open questions)
- [ ] Spring Boot skeleton (`api/`): Gradle, Flyway, Testcontainers, springdoc, Actuator
- [ ] Python ML service skeleton (`ml/`) with `/v1/embed`
- [ ] Postgres + pgvector schema and migrations ([DATA_MODEL.md](DATA_MODEL.md))
- [ ] Ingestion: fetch + snapshot + hash, extract, chunk, embed ([KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md))
- [ ] Reviewer UI (minimal): source verification, scheme content, two-reviewer publish
- [ ] Seed ~30 verified schemes (central + one state) with en + hi content
- [ ] Spring Boot read API: `/schemes`, `/schemes/{slug}`
- [ ] Scheme detail page: summary, documents, deadlines, apply link, sources with `verified_at`
- [ ] Copy lint and locale key-parity checks in CI

## Phase 2 — Rules engine and matching

- [ ] json-logic rule format, band evaluation and three-state combination ([RULES_ENGINE.md](RULES_ENGINE.md))
- [ ] Rule fixtures for every seeded scheme, with Java/JS parity in CI
- [ ] `/rules/bundle` and on-device matching
- [ ] Adaptive questionnaire (tap / type / speak) driven by `next_question`
- [ ] Results screen: likely / not eligible / need more info, with rule traces read aloud
- [ ] Family mode
- [ ] Stacking and conflict notes

## Phase 3 — RAG assistant (hi / en)

- [ ] Retrieval (pgvector + keyword, fused) with thresholds
- [ ] Prompt contract, JSON answer schema and validation ([RAG_ASSISTANT.md](RAG_ASSISTANT.md))
- [ ] "No official source" response
- [ ] Confidence labels and inline citations in the UI
- [ ] `LLMProvider` and `Embedder` interfaces with at least two LLM providers
- [ ] Evaluation set: 100+ items each in hi and en, run in CI with targets

## Phase 4 — Checklist, reminders, re-checks

- [ ] De-duplicated document checklist with have / don't have
- [ ] State-specific "how to get it" for each document
- [ ] Deadline and checklist reminders: first channel (Web Push or Telegram — see open questions)
- [ ] Daily source re-check job → `needs_review` and stale warnings
- [ ] Re-verification interval tasks
- [ ] "Report outdated info" and the moderation queue

## Phase 5 — Volunteer portal and OCR

- [ ] Threat model for the volunteer portal
- [ ] Volunteer verification and roles
- [ ] Consent-scoped, time-limited sessions via short code
- [ ] Field masking and the user-visible audit log ([PRIVACY_AND_SECURITY.md](PRIVACY_AND_SECURITY.md))
- [ ] Pilot with a partner NGO or CSC
- [ ] OCR document-type and readability check (delete after processing)

## Phase 6 — Scale out languages and channels

- [ ] IndicTrans2 content translation with human review flags
- [ ] Server-side STT (IndicWhisper) and TTS (Piper / Indic-TTS) fallbacks
- [ ] WhatsApp and/or SMS reminders
- [ ] CSC and office map (OpenStreetMap + Leaflet)
- [ ] Move the frontend to SvelteKit or Next.js (same locale JSON)
- [ ] Manual application tracking → anonymous rejection-reason insights
- [ ] More states

## Cross-cutting (every phase)

- [ ] Accessibility: Lighthouse + axe in CI, keyboard-only and screen-reader checks
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
