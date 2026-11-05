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
├── ml/         Python service for Hugging Face models: embeddings, speech, translation.
├── eval/       Questions that measure the assistant's retrieval (run in CI).
├── deploy/     Caddy and PostgreSQL setup for production.
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
| 10-language UI (en, hi, bn, ta, te, mr, gu, kn, ml, pa) with voice in and out and icon tiles | Built. Machine-authored; awaiting native-speaker review. The staff admin tool is English only |
| Eligibility questionnaire with three results and rule traces | Built (on-device rules engine) |
| Knowledge base | 49 published schemes: 37 central and 12 Uttar Pradesh, each approved by two independent AI reviewers ("Checked by AI reviewers"; no person has checked them yet). |
| Scheme text in all 10 languages | English and Hindi are reviewed. The other 8 are machine translated, labelled, with a button to read the English |
| State-specific "how to get it" for documents | Built for Uttar Pradesh, with official sources |
| Source re-check | Daily job; a changed source shows users a "may be outdated" warning and opens an issue. Page counters are told apart from real changes |
| Family mode, office slip, document checklist | Built |
| Application tracking and anonymous rejection insights | Built; insights show only once 10 or more people share |
| Photo check of a document | Built: OCR in the browser (English and Hindi), the photo never leaves the device |
| Reminders | Built: on the device, Web Push, Telegram, SMS and WhatsApp behind one interface. Providers need keys; only the on-device path has run for real |
| Nearby help | Built: search by PIN code (India Post's directory) or location, OpenStreetMap and Leaflet, official CSC locator linked |
| Saathi, the AI guide, and the cited assistant | Built; the rules engine decides and the model only explains. Works without a model by tapping answers |
| Search across languages | Built: multilingual-e5 embeddings fused with keywords in `ml/`, falling back to keywords |
| Server speech for devices without voices | Built in `ml/`: text-to-speech in all 10 languages (MMS); speech-to-text in English, Hindi and Tamil only (Whisper is not reliable in the others) |
| Helper portal | Built: consent-scoped sharing, sensitive answers masked unless allowed, audit log, report-a-helper |
| Accounts and security | Phone sign-in (MSG91 or Twilio), encrypted data, staff accounts with authenticator codes, insert-only audit log, retention jobs, under-18 on-device rule, grievance form |
| Admin and moderation | Built at `#/admin`; publishing stays in `tools/publish.mjs` |
| Operations | Docker Compose with PostgreSQL, Caddy TLS, Prometheus metrics, error reporting. See [docs/OPERATIONS.md](docs/OPERATIONS.md) |

See [docs/ROADMAP.md](docs/ROADMAP.md) for what is left, and [HANDOVER.md](HANDOVER.md) for what has and has not been exercised live.

## Quick start

Requires Node 20.19+ (the root tooling has no dependencies; `npm run setup` installs the frontend's), Java 25 for the backend, and a modern browser. Chrome or Android Chrome gives the best speech support.

```bash
npm run setup && npm run dev   # open http://localhost:5173

npm run check            # content validation, copy lint, locale parity, bundle freshness, unit tests
npm run test:backend     # Java engine on the shared fixtures, API tests, retrieval evaluation
npm run build:jar        # one jar with the PWA and the API: java -jar backend/build/libs/api-0.0.1-SNAPSHOT.jar
docker compose up --build   # the same with PostgreSQL; set the values in .env.example first
docker compose --profile ml up   # adds the model service (see ml/README.md)
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
