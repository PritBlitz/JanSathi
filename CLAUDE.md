# CLAUDE.md — operating guide for AI sessions

JanSathi is a multilingual, voice-first navigator for Indian government schemes. **Read [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) first; it is the source of truth.** If the brief and the code disagree, ask the maintainer which is right. Don't guess.

## Non-negotiable principles

1. Never write copy that says "you are eligible", "you will get" or "guaranteed". Use "you may be eligible", "likely relevant" or "need more information".
2. Never show a scheme fact without a linked source record carrying `source_url`, `fetched_at` and `verified_at`.
3. No official source found → say "I couldn't find an official source for this". Never answer from model memory.
4. Out-of-date facts (source changed, or past the re-verification interval) carry a visible warning.
5. Every user-facing string goes through i18n (`data-i18n` / `data-i18n-attr` + `web/locales/*.json`). No hard-coded UI text.
6. Every interactive element works with voice/TTS and has an accessible label.
7. Collect the minimum personal data. The profile stays on the device where possible; the user can delete everything.
8. Never store Aadhaar or bank account numbers. Documents are "have / don't have" unless the user uploads for OCR; uploads are deleted after processing.

Also: **the rules engine decides eligibility; the LLM only explains.** Never let model output change a match result.

## Repo layout

```
CLAUDE.md, README.md, DESIGN.md
docs/                 # brief + design docs (see README for the index)
web/                  # Phase 0 static PWA prototype (no build step)
  index.html
  css/styles.css
  js/i18n.js          # LANGS, locale loader, data-i18n, persistence
  js/speech.js        # speak, stop, listen, hasVoice
  js/app.js           # wiring
  locales/<code>.json # en hi bn ta te mr gu kn ml pa — identical key sets
```

Planned (not built): `api/` (Spring Boot 3, Java 21, Gradle; Flyway migrations inside it), `ml/` (internal Python service for embeddings, IndicTrans2, STT/TTS, OCR), `ingest/` (Python), `rules/` (shared fixtures), `eval/`. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Decided: backend is **Spring Boot**, not FastAPI (2026-10-24). Python is used only for the ML service and ingestion.

## Run and test

```bash
cd web && python3 -m http.server 8000   # frontend prototype
```

There is no automated test suite yet. Planned CI checks: rule unit tests, RAG evaluation set, copy lint for banned phrases, locale key parity, Lighthouse + axe. See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md).

## Working rules

- When adding a UI string, add the key to **all 10** locale files (English text as a placeholder, marked for review) so key sets stay identical.
- Follow [DESIGN.md](DESIGN.md) for visuals: tokens, hero, motion limits, performance budget.
- Keep every external provider (LLM, embeddings, translation, STT/TTS, OCR, notifications) behind an interface.
- Docs must say what exists now and mark everything else as planned.
- Commit only when asked.
