# JanSathi

**A multilingual, voice-first guide to Indian government schemes.**

JanSathi helps people find schemes they may qualify for, understand the rules in their own language, prepare the right documents and meet deadlines. Every answer links to an official source and shows when that source was last verified.

> JanSathi is not a government service. It never promises eligibility or approval; final decisions are always made by the government.

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
| 10-language UI (en, hi, bn, ta, te, mr, gu, kn, ml, pa) with voice in/out and icon tiles | Phase 0 prototype |
| Eligibility questionnaire with three results: likely eligible / not eligible / need more info | Planned (Phase 2) |
| Family mode and scheme stacking/conflicts | Planned (Phase 2) |
| Verified knowledge base with source re-checks and outdated-info warnings | Planned (Phases 1, 4) |
| Cited AI assistant in Hindi and English | Planned (Phase 3) |
| Personal document checklist with "how to get it" guidance | Planned (Phase 4) |
| Deadline reminders (Web Push, Telegram) | Planned (Phase 4) |
| Volunteer portal with consent sessions and audit log | Planned (Phase 5) |
| OCR document check | Planned (Phase 5) |
| Nearby CSC and office map | Planned (Phase 6) |

See [docs/ROADMAP.md](docs/ROADMAP.md) for the full plan.

## Status

Phase 0: a static, no-build frontend prototype (`web/`) with the multilingual voice-first UI. Its category tiles and quick check are placeholders and are not yet connected to data. There is no backend, database or AI component yet. Everything else in the docs is the planned design.

## Quick start (frontend prototype)

Requires Python 3 (only for the local web server) and a modern browser. Chrome or Android Chrome gives the best speech support.

```bash
cd web
python3 -m http.server 8000
# open http://localhost:8000
```

## Documentation

| Doc | What's in it |
|---|---|
| [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) | Vision, scope and rules. The source of truth. |
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
