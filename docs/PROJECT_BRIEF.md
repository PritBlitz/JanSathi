# JanSathi — Project Brief

> **How to use this file:** give it to a Claude Code session in the JanSathi repo. It is the source of truth for the vision, scope, architecture and rules. If this brief and the code ever disagree, ask the maintainer which one is right; don't guess.
>
> **Later decisions override parts of this brief. See [§17](#17-maintainer-decisions-added-after-the-brief).** (Most importantly: the backend is Spring Boot, not FastAPI.)

## 1. One-line summary

JanSathi is a multilingual, voice-first navigator for Indian government schemes. It helps people find schemes they may qualify for, understand the rules in their own language, prepare the right documents and meet deadlines. Every answer links to an official source and shows when that source was last verified.

## 2. Problem

People who qualify for scholarships, pensions, health cover, housing or farming support often never apply, because:

- they don't know the scheme exists, or think they don't qualify;
- eligibility rules are long, legalistic and usually only in English or Hindi;
- required documents and procedures are unclear, so applications get rejected or abandoned;
- deadlines and application windows are missed;
- many users have low literacy, use basic phones or have patchy internet;
- the middlemen who "help" sometimes charge fees or misuse personal data.

## 3. What makes JanSathi different

It is a reliable discovery engine, not a chatbot:

- **The rules engine decides; the AI only explains.** Eligibility is checked by structured, testable rules. The LLM never decides who is eligible.
- **Every claim is cited.** Each answer gives the official source URL, the source date and a last-verified date.
- **Uncertainty is visible.** Each answer is labelled *Confirmed rule*, *Official FAQ / guidance* or *Unverified / may be outdated*.
- **It never promises eligibility or approval.** The approval decision always belongs to the government.
- **Voice and icons first.** Speech in and out, large picture tiles and short sentences, so people who can't read long text can still use it.
- **Privacy by default.** Minimum data, consent-scoped volunteer access, and Aadhaar numbers are never stored.

## 4. Target users

| Persona | Need |
|---|---|
| Rural farmer, low literacy, Android phone | Voice in their regional language; farming and crop-insurance schemes |
| Student, 16–24 | Scholarships, application deadlines, document checklist |
| Elderly person or their caregiver | Pension schemes; a family member or volunteer helps |
| Woman head of household | Schemes for women and children, housing, health |
| Person with a disability | Disability-specific schemes; accessible UI |
| Volunteer, NGO worker or CSC operator | Help several people without seeing more personal data than needed |

## 5. Non-negotiable principles (product and engineering rules)

1. Never write copy that says "you are eligible" or "you will get". Use "you may be eligible", "likely relevant" or "need more information".
2. Never show a scheme fact without a linked source record carrying `source_url`, `fetched_at` and `verified_at`.
3. If retrieval finds no good official source, the assistant says so ("I couldn't find an official source for this") and does not answer from model memory.
4. Facts that are out of date (source changed, or past the re-verification interval) carry a visible warning.
5. Every user-facing string goes through i18n. No hard-coded UI text.
6. Every interactive element works with voice or text-to-speech and has an accessible label.
7. Collect the minimum personal data. The profile stays on the device where possible, and the user can delete everything.
8. Never store Aadhaar or bank account numbers. A document is only marked "have it / don't have it" unless the user explicitly uploads it for OCR, and uploads are deleted after processing by default.

## 6. Key features

### 6.1 Eligibility discovery
- Short adaptive questionnaire (age, state, income band, occupation, category, gender, disability, student status, family members).
- Questions can be answered by tapping an icon, typing or speaking.
- Each scheme gets one of three results:
  - **Likely eligible:** all known rules pass.
  - **Not eligible:** a hard rule fails; show which rule and why.
  - **Need more info:** list the exact questions that are still unanswered.
- Family mode: one household profile gives matches for each family member.
- Stacking and conflicts: show which schemes can be combined and which exclude each other.

### 6.2 Multilingual AI assistant
- Explains rules simply (aim for a grade-5 reading level), in the user's language.
- Uses RAG over the verified knowledge base only, and cites sources inline.
- Labels each answer: confirmed / guidance / uncertain.
- Voice in and out.

### 6.3 Verified knowledge base
- Schemes, rules, documents, deadlines and sources, stored as structured data.
- Ingestion pipeline: fetch official page or PDF → extract text → a human reviewer checks it and encodes rules → publish.
- A scheduled re-check computes a hash of each source; if it changes, the scheme is marked `needs_review` and users see a warning.
- "Report outdated info" button sends a report to a moderation queue.

### 6.4 Document checklist
- A personalised list of documents for the schemes the user picked, de-duplicated across schemes.
- The user marks each one have / don't have.
- For documents the user doesn't have, show how to get them (office, online portal, typical time). This is state-specific.
- Optional OCR check that an uploaded document is the right type and is readable. OCR does not verify authenticity.

### 6.5 Deadline reminders
- Application windows per scheme (state-specific where relevant).
- Reminders for upcoming deadlines and unfinished checklist items.
- Channels: Web Push, Telegram bot, and SMS or WhatsApp later.

### 6.6 Assisted-service (volunteer) portal
- Volunteers are verified and given a role.
- The user grants a consent-scoped, time-limited session (for example by sharing a short code).
- Volunteers see only the fields they need. Sensitive fields are masked.
- Full audit log of who viewed or changed what, and when. The user can see it.

### 6.7 Application tracking
- Manual tracking first: applied, under review, approved or rejected, with a rejection reason.
- Official status integrations only where a public, permitted API exists.
- Anonymous outcome data later shows common rejection reasons for each scheme.

### 6.8 Nearby help
- Map of Common Service Centres (CSCs) and relevant offices, using OpenStreetMap and Leaflet.

## 7. Languages, voice and icon UI

**Status:** built in `frontend/` (React + Vite since 2026-10-25, see §17); run it with `npm run setup && npm run dev`.

- **Languages (10):** English (en), Hindi (hi), Bengali (bn), Tamil (ta), Telugu (te), Marathi (mr), Gujarati (gu), Kannada (kn), Malayalam (ml), Punjabi (pa). Each has a BCP-47 tag `xx-IN` used for speech.
- **i18n mechanism:**
  - Elements use `data-i18n="key"` for text and `data-i18n-attr="attr:key,attr2:key2"` for attributes such as placeholder and aria-label.
  - Strings live in `frontend/public/locales/<code>.json`.
  - Missing keys fall back to English.
  - The language is detected from the browser and then remembered in `localStorage`.
  - Changing language also updates `<html lang>` and the page title.
- **Text-to-speech:** the browser Web Speech API (`speechSynthesis`) reads the whole page, a section, or the name of a tapped tile. It warns when the device has no voice for the chosen language.
- **Speech-to-text:** the browser `SpeechRecognition` (best in Chrome and Android), with a typing fallback.
- **Icon UI:** large category tiles with emoji icons, touch targets of at least 48px, Noto fonts for every script, dark mode and visible focus.
- **Known gaps:**
  - The translations were machine-authored and need native-speaker review.
  - Browser voices are missing for several languages on desktop.
  - Scheme content (dynamic data) still needs a translation pipeline.
- **Planned upgrades:**
  - Server-side IndicWhisper for speech-to-text and Piper or AI4Bharat Indic-TTS for text-to-speech, as a fallback when the browser lacks a language.
  - IndicTrans2 to translate scheme content, with cached results and a human review flag.
  - Move to SvelteKit or Next.js once routing and state are needed, keeping the same locale JSON format.

## 8. Recommended tech stack (free / open source)

| Layer | Choice | Notes |
|---|---|---|
| Frontend | Static PWA today → SvelteKit (or Next.js) | Offline-capable PWA; hosted on Cloudflare Pages or Vercel free tier |
| Backend API | FastAPI (Python) | Fits the AI, OCR and NLP libraries |
| Database | PostgreSQL via Supabase free tier (or Neon) | Supabase adds auth and row-level security |
| Vectors | pgvector | Same database, no separate vector store |
| Embeddings | BAAI/bge-m3 or intfloat/multilingual-e5 | Good on Indian languages; run locally |
| LLM | Groq or Gemini free tier, or local Ollama (Llama / Qwen) | Behind a provider interface so it can be swapped |
| Translation | AI4Bharat IndicTrans2 | Open source |
| Speech | Web Speech API → IndicWhisper / Whisper (STT), Piper / Indic-TTS (TTS) | |
| OCR | PaddleOCR or Tesseract (hin and other language packs), optionally docTR | |
| Rules engine | json-logic (json-logic-py / json-logic-js) | Same rules can run client-side and server-side |
| Ingestion | Playwright + trafilatura, PyMuPDF for PDFs | Respect robots.txt and site terms |
| Scheduled jobs | GitHub Actions cron or Supabase pg_cron | |
| Notifications | Web Push, Telegram Bot API; WhatsApp Cloud API later | |
| Maps | OpenStreetMap + Leaflet | |
| Monitoring | Sentry free tier, Grafana Cloud free tier | |
| CI/CD | GitHub Actions | |

Free-tier quotas change. Check current limits before depending on one, and keep every external provider behind an interface.

## 9. Architecture (high level)

```
 PWA (frontend/)  ──►  FastAPI  ──►  Postgres + pgvector (Supabase)
   │  i18n, STT/TTS     │  /schemes /match /ask /checklist /reminders /volunteer
   │  rules (client)    ├─► Rules engine (json-logic)  ← deterministic eligibility
   │                    ├─► RAG: retrieve (pgvector) → LLM explain → cite
   │                    ├─► Translation (IndicTrans2), OCR (PaddleOCR)
   │                    └─► Notifications (Web Push / Telegram)
 Scheduled jobs: source re-fetch + hash diff → needs_review; deadline reminders
 Admin / reviewer UI: verify sources, encode rules, publish
```

Request flow for "Am I eligible for X?":

1. The rules engine evaluates X's rules against the profile and returns likely, not eligible or need-more-info, along with the rule trace.
2. RAG retrieves passages from X's verified sources.
3. The LLM writes a short explanation in the user's language, using only the retrieved passages and the rule trace, and cites them.
4. The response includes the confidence label, source links, `verified_at` and the disclaimer.

## 10. Data model (starting point)

- `scheme`: id, slug, level (central/state), state_code, ministry, category, status (draft / published / needs_review / archived), benefit_summary, official_apply_url, created_at, updated_at
- `scheme_translation`: scheme_id, lang, name, summary, how_to_apply, machine_translated (bool), reviewed_by, reviewed_at
- `source`: id, scheme_id, url, title, publisher, doc_type (gazette / guideline / faq / portal / secondary), published_date, fetched_at, content_hash, verified_at, verified_by
- `rule`: id, scheme_id, json_logic, description_key, is_hard (bool), source_id, confidence (confirmed / guidance / uncertain)
- `document_type`: id, code (e.g. income_certificate), how_to_obtain (per state, translatable)
- `scheme_document`: scheme_id, document_type_id, mandatory (bool), notes
- `deadline`: scheme_id, state_code, opens_at, closes_at, recurring_rule, source_id
- `chunk`: id, source_id, lang, text, embedding (vector)
- `user_profile`: id, lang, coarse fields only (age band, state, income band…); optional, encrypted, deletable
- `checklist_item`, `reminder`, `application` (user-side tracking)
- `volunteer`, `assist_session` (scope, expires_at, user consent), `audit_log`
- `report` (user-reported outdated info) and `review_task`

## 11. MVP scope (build this first)

- One state plus central schemes, about 30 schemes, manually verified with sources.
- Questionnaire → rules engine → three-state results with rule traces.
- Scheme detail page: summary, documents, deadlines, apply link and sources with `verified_at`.
- Document checklist (have / don't have) and how to obtain each item.
- Cited RAG assistant in Hindi and English, with the "no source, no answer" rule.
- Source re-check job and `needs_review` warnings.
- Keep the 10-language UI and voice that are already built.

Out of the MVP: volunteer portal, OCR, official status integrations, WhatsApp, more languages for scheme content.

## 12. Roadmap

| Phase | Deliverables |
|---|---|
| 0 ✅ | Multilingual voice-first frontend prototype (`frontend/`) |
| 1 | Data model, seed 30 verified schemes, FastAPI read API, scheme pages |
| 2 | Rules engine and questionnaire, three-state matching, family mode |
| 3 | RAG assistant (hi/en) with citations and confidence labels; evaluation set |
| 4 | Checklist and reminders (Web Push / Telegram); scheduled source re-checks |
| 5 | Volunteer portal with consent sessions and audit log; OCR document check |
| 6 | More languages for content (IndicTrans2 + human review), server-side speech, WhatsApp/SMS, CSC map |

## 13. Quality, safety and evaluation

- **Rules tests:** every scheme rule has unit tests with edge cases (boundary ages, income limits).
- **RAG evaluation set:** 100+ question / expected-answer / expected-source triples in hi and en. Track citation accuracy, rate of answers with no source, and hallucination rate. Run it in CI.
- **Copy lint:** CI check that blocks banned phrases ("you are eligible", "guaranteed", "you will get") in locale files and prompts.
- **Accessibility:** Lighthouse and axe checks, keyboard-only navigation, screen-reader labels.
- **Privacy:** threat model for the volunteer portal; data-retention policy; DPDP Act 2023 (India) alignment.
- **Content governance:** a human reviewer approves every published rule and translation; `verified_by` is recorded.
- **Scraping ethics:** respect robots.txt and site terms; rate-limit requests; prefer official open data (e.g. data.gov.in, myScheme where permitted).

## 14. Docs to generate

- `README.md` (root): pitch, features, status, quick start (run `frontend/`), links to the docs.
- `docs/ARCHITECTURE.md`: components, request flows, the diagram from §9 expanded, provider interfaces.
- `docs/DATA_MODEL.md`: tables from §10 with columns, types, relations and a Mermaid ER diagram.
- `docs/RULES_ENGINE.md`: json-logic rule format, three-state evaluation, rule traces, two worked examples, testing.
- `docs/KNOWLEDGE_BASE.md`: source types, ingestion and verification workflow, re-check job, confidence labels, citation format.
- `docs/RAG_ASSISTANT.md`: retrieval, prompt contract ("answer only from passages, cite, else say not found"), answer schema, evaluation.
- `docs/I18N_AND_VOICE.md`: how the existing `frontend/` i18n works, how to add a language or key, STT/TTS behaviour and fallbacks, the translation-review process.
- `docs/PRIVACY_AND_SECURITY.md`: data minimisation, what is never stored, volunteer consent sessions, audit log, retention, DPDP notes.
- `docs/ROADMAP.md`: phases from §12 as checklists.
- `docs/CONTRIBUTING.md`: setup, coding conventions, the non-negotiable principles from §5, copy rules, PR checklist.
- `CLAUDE.md` (root): short operating guide for AI sessions: principles from §5, repo layout, how to run and test, where docs live.

Keep the docs factual about what exists now (only `frontend/` is built) and mark everything else as planned.

## 15. Current repo state

```
README.md            # placeholder
frontend/
  index.html         # icon-driven page: hero, category tiles, voice ask box, quick check
  css/styles.css     # large touch targets, Noto fonts, dark mode
  js/i18n.js         # LANGS list, locale loader, data-i18n application, persistence
  js/speech.js       # Web Speech API wrappers: speak, stop, listen, hasVoice
  js/app.js          # wiring: language select, read-aloud, mic, tiles
  locales/*.json     # en hi bn ta te mr gu kn ml pa (identical key sets)
```

The tiles and quick check are placeholders and are not yet connected to data.

## 16. Open questions for the maintainer

1. Which state should the MVP cover first?
2. Will the backend be FastAPI (recommended) or Spring Boot (original idea)?
3. Is user sign-in needed in the MVP, or should the app be anonymous with an on-device profile?
4. Which partners (NGO or CSC) could pilot the volunteer portal?
5. Which reminder channel should come first: Web Push or Telegram?

## 17. Maintainer decisions (added after the brief)

These override the brief where they differ.

| Date | Decision | Replaces |
|---|---|---|
| 2026-10-24 | Backend is **Spring Boot** (Java 21). Python is used only for an internal ML service (embeddings, IndicTrans2, STT/TTS, OCR) and the ingestion job. Rules run on json-logic-java + json-logic-js. | "FastAPI (Python)" in §8, §9, §12 and open question 2 |
| 2026-10-24 | The hero display word is the brand name written in the active language's script (JanSathi, जनसाथी, …). | — (design detail, see DESIGN.md) |
| 2026-10-26 | Accounts by **phone + one-time code**. Each person gets **Saathi, an AI guide** (Google Gemini via its OpenAI-compatible API) that asks the questions and explains results with sources, using the rules engine only. Registered **human helpers** (NGO/CSC) can help through a consent-scoped portal. Scheme reviews are done by **two independent AI reviewer agents**; published schemes say "Checked by AI reviewers". | §6.6 volunteer portal (now with AI + human helpers); §16 open questions 3 (sign-in) and the reviewer question; "human reviewer" in §5 and §13 for scheme verification |
| 2026-10-25 | Frontend is **React** (Vite build). Same locale JSON; strings go through `t('key')` instead of `data-i18n` attributes. The site is redesigned with a new palette; the hero anatomy stays. Java is 25 and Spring Boot 4.1. | "Static PWA today → SvelteKit (or Next.js)" in §7, §8; the `data-i18n` mechanism in §7 |
