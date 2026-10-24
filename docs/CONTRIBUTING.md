# Contributing to JanSathi

Thanks for helping. JanSathi is used by people making real decisions about money, pensions and scholarships, so a few rules here are strict. Please read this whole page before your first PR.

## 1. Setup

What exists today is the static frontend prototype.

```bash
git clone <repo-url>
cd JanSathi/web
python3 -m http.server 8000
# open http://localhost:8000 (use Chrome or Android Chrome for speech)
```

No build step, no package install. To test on a phone, open the same URL from a device on your network, or use Chrome's remote debugging. Note that speech recognition requires HTTPS or `localhost`.

Backend, database and AI setup will be added here as those phases land (see [ROADMAP.md](ROADMAP.md)).

## 2. Non-negotiable principles

Every change must keep these true. A PR that breaks one will not be merged.

1. **Never** write copy that says "you are eligible" or "you will get". Use "you may be eligible", "likely relevant" or "need more information".
2. **Never** show a scheme fact without a linked source record carrying `source_url`, `fetched_at` and `verified_at`.
3. If retrieval finds no good official source, the assistant says *"I couldn't find an official source for this"* and does **not** answer from model memory.
4. Out-of-date facts (source changed, or past the re-verification interval) carry a visible warning.
5. Every user-facing string goes through i18n. No hard-coded UI text.
6. Every interactive element works with voice or text-to-speech and has an accessible label.
7. Collect the minimum personal data. The profile stays on the device where possible, and the user can delete everything.
8. **Never** store Aadhaar or bank account numbers. Documents are only "have it / don't have it" unless the user explicitly uploads one for OCR, and uploads are deleted after processing by default.

And the architectural rule behind them: **the rules engine decides; the AI only explains.**

## 3. Copy rules

Applies to locale files, prompts, scheme content, notifications and docs shown to users.

- **Reading level:** grade 5. Short sentences (aim for ≤ 15 words), common words, active voice, second person.
- **Banned phrases** (and their equivalents in every language): "you are eligible", "you will get", "guaranteed", "100%", "approved", "sure to". The copy lint blocks them.
- **Prefer:** "you may be eligible", "this scheme may fit you", "we need a bit more information", "this scheme may not fit you, because…".
- **Be honest about uncertainty.** If we're not sure, say so and show the label.
- **Final decisions belong to the government.** Say so where a decision is implied.
- **Help is free.** Never imply that users must pay anyone to apply.
- No jargon, acronyms or scheme codes without a plain explanation the first time.
- Numbers and dates via `Intl` formatting, not string concatenation.

## 4. Coding conventions

### Frontend (`web/`)

- Plain HTML, CSS and ES modules; no build step until the planned SvelteKit/Next.js move.
- All text via `data-i18n` / `data-i18n-attr`. When you add a key, add it to **all 10** locale files (English copy is fine as a placeholder; list those keys in the PR).
- Follow [DESIGN.md](../DESIGN.md): use the CSS tokens, keep touch targets ≥ 48 px, respect `prefers-reduced-motion`, and stay within the performance budget.
- Semantic HTML first; ARIA only when there's no native element. Every icon-only button has an `aria-label` from i18n.
- Wrap every `localStorage` access in `try/catch`; the app must work without it.
- Check new UI in English, Hindi and the longest locale (Tamil or Malayalam), at 360 px and 1280 px, in light and dark.

### Backend API (`api/`, planned)

- Java 21, Spring Boot 3, Gradle (Kotlin DSL). Base package `org.jansathi`, organised by feature (`scheme`, `rules`, `rag`, …), not by layer.
- Request and response bodies are Java `record`s with Bean Validation annotations. Never expose database entities directly.
- Constructor injection only (no field `@Autowired`).
- Formatting with Spotless + google-java-format; the build fails on unformatted code.
- External services only through the provider interfaces in [ARCHITECTURE.md](ARCHITECTURE.md#6-provider-interfaces), with fakes for tests.
- Tests: JUnit 5 + AssertJ; database tests with Testcontainers (Postgres + pgvector); WireMock for the ML service and LLM.
- Flyway migrations are forward-only and reviewed.
- No personal data in logs (a Logback filter masks Aadhaar/bank/phone patterns as a safety net).

### Python services (`ml/`, `ingest/`, planned)

- Python 3.12. `ruff` for formatting and linting, `mypy` for types.
- `ml/` is stateless: no database access, no public route, no storage of inputs.
- `ingest/` follows the scraping rules below.

### Rules and content

- One condition per rule, allowed operators only, a source clause for every rule ([RULES_ENGINE.md](RULES_ENGINE.md)).
- Fixtures with boundary cases for every rule.
- Content changes go through the two-reviewer flow in [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md).

### Scraping

Respect `robots.txt` and site terms, rate-limit (≤ 1 request / 5 s per host), identify the crawler, and prefer official open data where available.

## 5. Git and PRs

- Branch from `main`: `feat/…`, `fix/…`, `docs/…`, `content/<scheme-slug>`, `i18n/<lang>`.
- Small, focused PRs. Use clear commit messages in the imperative ("Add Tamil review fixes").
- Docs must describe what exists now and mark planned work as planned.

### PR checklist

Copy this into your PR description:

```markdown
- [ ] No banned phrases; copy is grade-5 and honest about uncertainty
- [ ] Every new user-facing string is an i18n key, present in all 10 locale files
- [ ] Every new interactive element has an accessible label and works with voice/TTS
- [ ] Every scheme fact shown links to a source with source_url, fetched_at, verified_at
- [ ] No new personal data collected (or: justified, minimised, deletable, documented in PRIVACY_AND_SECURITY.md)
- [ ] No Aadhaar / bank numbers stored or logged
- [ ] Rules: fixtures with boundary cases; Java/JS parity passes
- [ ] UI: checked in en + hi + ta/ml, 360 px + 1280 px, light + dark, keyboard only
- [ ] UI: names the Awwwards pattern used (link) and stays within the performance budget (DESIGN.md §10)
- [ ] Lighthouse + axe: no new accessibility errors
- [ ] Docs updated
```

## 6. Translators and reviewers

Native-speaker review is one of the most useful contributions. See [I18N_AND_VOICE.md](I18N_AND_VOICE.md#6-translation-review). You don't need to write code: open an issue for your language and we'll share the strings with English and screenshots.

## 7. Planned CI checks

| Check | What it does |
|---|---|
| Copy lint | Blocks banned phrases in locale files, prompts and seed content (all languages) |
| Locale parity | All 10 locale files have identical key sets |
| Rules | Fixtures pass in Java and JS, with identical outcomes |
| Backend | `./gradlew check` (Spotless, unit and Testcontainers tests) |
| RAG eval | Metrics in [RAG_ASSISTANT.md](RAG_ASSISTANT.md#7-evaluation) meet targets |
| Accessibility | Lighthouse and axe on key pages |
| Security | Dependency and secret scanning |
