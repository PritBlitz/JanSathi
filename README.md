# JanSathi

**A multilingual, voice-first guide to Indian government schemes.**

JanSathi helps people find schemes they may qualify for, understand the rules in their own language, prepare the right documents and meet deadlines. Every answer links to an official source and shows when that source was last verified.

> JanSathi is not a government service. It never promises eligibility or approval; final decisions are always made by the government.

## Start here

```
JanSathi/
├── frontend/   The app people use: React + Vite PWA. Works offline; eligibility is checked on the phone.
├── backend/    Java 25 + Spring Boot API (Gradle). Serves the same scheme data and matching over HTTP.
├── content/    The knowledge base: every scheme as a JSON file, with its official sources.
├── rules/      Test cases for the eligibility rules (both frontend and backend must pass them).
├── ingest/     Python job that re-downloads official sources and detects changes.
├── tools/      Node scripts: tests, validators, bundle builder, dev server.
├── eval/       Questions that measure the assistant's retrieval (run in CI).
└── docs/       Product brief and design documents.
```

**Run the app** (needs Node 20.19+):

```bash
npm run setup            # once: installs React and Vite into frontend/
npm run dev              # dev server with hot reload on http://localhost:5173
npm start                # or: production build served on http://localhost:8000
```

**Run the backend** (needs Java 25; the Gradle wrapper downloads everything else):

```bash
npm run start:backend    # same as: cd backend && gradlew bootRun   → http://localhost:8080
```

With both running, `npm run dev` sends `/api` calls to the backend (the ask sheet then shows cited rule passages). Without the backend, the app uses its built-in data. API reference: http://localhost:8080/v3/api-docs.

**Check everything:** `npm run check` (data, translations, 300+ tests) and `npm run smoke` (opens the app in headless Chrome).

---

## Why

Many people who qualify for scholarships, pensions, health cover, housing or farming support never apply. They don't know a scheme exists, the rules are long and only in English or Hindi, the documents are unclear, deadlines pass, and paid middlemen sometimes misuse their data. JanSathi is built for low-literacy users on basic phones with patchy internet, and for the volunteers who help them.

## What makes it different

- **Rules decide, AI explains.** Eligibility is checked by structured, testable [json-logic](https://jsonlogic.com/) rules. The language model only explains the result; it never decides who is eligible.
- **Every claim is cited.** Each fact shows its official source URL, the source date and when it was last verified.
- **Uncertainty is visible.** Answers are labelled *Confirmed rule*, *Official guidance* or *Unverified / may be outdated*.
- **No source, no answer.** If there's no official source, the assistant says so instead of guessing.
- **Voice and icons first.** Speech in and out, large picture tiles and short sentences, in 10 languages.
- **Privacy by default.** Minimum data, on-device profile where possible, consent-scoped volunteer access. Aadhaar and bank account numbers are never stored.

## Features

| Feature | Status |
|---|---|
| 10-language UI (en, hi, bn, ta, te, mr, gu, kn, ml, pa) with voice in/out and icon tiles | Built (`frontend/`); 9 locales await native-speaker review |
| Eligibility questionnaire with three results: likely eligible / not eligible / need more info, with rule traces | Built (on-device rules engine, `frontend/src/lib/rules.js`) |
| Scheme pages with official sources, clause locators and checked/verified dates | Built; 28 central schemes, all **drafts** awaiting human review |
| Source re-check job (hash changes → review) | Built; runs daily in GitHub Actions and opens an issue when a source changes |
| Personal document checklist with "how to get it" guidance | Built (generic, all-India guidance) |
| Family mode: household questions once, each member's own results | Built (on the device) |
| Office slip: schemes and documents to carry, print or share | Built |
| Scheme stacking/conflicts | Planned: no relations recorded in content yet |
| Cited AI assistant in Hindi and English | Built: retrieval of official rule passages, an optional LLM explanation (any OpenAI-compatible provider), validation against the copy rules, and "no official source" when nothing matches. Off until a model is configured |
| Spring Boot API: schemes, matching, rule bundle, checklist, search, assistant, reports and their moderation | Built (`backend/`), with PostgreSQL (H2 locally) and Docker |
| Deadline reminders (Web Push, Telegram) | Planned: no deadlines recorded in content yet |
| Volunteer portal with consent sessions and audit log | Planned (Phase 5) |
| OCR document check | Planned (Phase 5) |
| Nearby CSC and office map | Planned (Phase 6) |

See [docs/ROADMAP.md](docs/ROADMAP.md) for the full plan.

## Status

What exists now (October 2026):

- **`frontend/`**: a React + Vite PWA (redesigned 2026-10-25, see [DESIGN.md](DESIGN.md)). Questionnaire, three-state results with rule traces, scheme pages with sources, document checklist, search, read-aloud and voice answers, 10 languages, offline support. All matching happens on the device; nothing about the user leaves the phone.
- **`content/`**: 28 central schemes across pensions, insurance, farming, health, housing, food, work, energy, women and children, disability and scholarships (see `content/schemes/`). Every rule cites the clause of an official source it rests on, and each source has `fetched_at` and a content hash. **They are drafts:** a human reviewer has not yet set `verified_at`, so the app runs in *preview mode* and labels every result "may be outdated".
- **`rules/`**: shared fixtures (233 cases) that both the JS and the Java engine pass.
- **`ingest/recheck.py`** and **`tools/`**: source re-check, content validator, copy lint (10 languages), locale parity, bundle builder, browser smoke test.
- **`backend/`**: Spring Boot 4.1 API under `/api/v1`: schemes, categories, match, rule bundle (ETag-cached), checklist, search, the assistant (`/ask`), outdated-info reports with an admin moderation queue, OpenAPI. Its Java rules engine gives the same results as the JS one on every fixture. Flyway migrations on PostgreSQL (H2 locally). One jar or Docker image serves the PWA and the API.
- **CI** (`.github/workflows/`): every push runs the content checks, both engines, the API tests, the retrieval evaluation, a headless-Chrome walk-through in English and Tamil, and a Docker build; a daily job re-checks every official source.

Is the idea feasible? See [docs/FEASIBILITY.md](docs/FEASIBILITY.md): yes for the technology; the real constraint is human review of content and translations.

## Quick start

Requires Node 20.19+ (the root tooling has no dependencies; `npm run setup` installs the frontend's), Java 25 for the backend, and a modern browser. Chrome or Android Chrome gives the best speech support.

```bash
npm run setup && npm run dev   # open http://localhost:5173

npm run check            # content validation, copy lint, locale parity, bundle freshness, 312 unit tests
npm run test:backend     # Java engine on the shared fixtures, API tests, retrieval evaluation
npm run build:jar        # one jar with the PWA and the API: java -jar backend/build/libs/api-0.0.1-SNAPSHOT.jar
docker compose up --build   # the same with PostgreSQL (set POSTGRES_PASSWORD first)
npm run smoke            # build, then a headless-Chrome walk through the app (needs Chrome or Edge)
python ingest/recheck.py # re-fetch every official source and report changes
```

## Documentation

| Doc | What's in it |
|---|---|
| [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) | Vision, scope and rules. The source of truth. |
| [docs/FEASIBILITY.md](docs/FEASIBILITY.md) | Can it be built and run? Risks and recommendation |
| [DESIGN.md](DESIGN.md) | Visual design system and the hero component |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, request flows, provider interfaces |
| [docs/DATA_MODEL.md](docs/DATA_MODEL.md) | Tables, relations, ER diagram |
| [docs/RULES_ENGINE.md](docs/RULES_ENGINE.md) | Rule format, three-state evaluation, rule traces |
| [docs/KNOWLEDGE_BASE.md](docs/KNOWLEDGE_BASE.md) | Sources, ingestion, verification, re-checks, citations |
| [docs/RAG_ASSISTANT.md](docs/RAG_ASSISTANT.md) | Retrieval, prompt contract, answer schema, evaluation |
| [docs/I18N_AND_VOICE.md](docs/I18N_AND_VOICE.md) | Languages, locale files, speech in and out |
| [docs/PRIVACY_AND_SECURITY.md](docs/PRIVACY_AND_SECURITY.md) | Data minimisation, consent sessions, audit log, DPDP |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phases as checklists, open questions |
| [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) | Setup, conventions, copy rules, PR checklist |
| [CLAUDE.md](CLAUDE.md) | Operating guide for AI coding sessions |

## Contributing

Read [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) first. In particular, the non-negotiable principles and copy rules apply to every change. Native-speaker reviews of the translations are especially welcome.
