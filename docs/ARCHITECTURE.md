# Architecture

> **Status:** only the frontend prototype (`web/`) exists. Everything else on this page is the **planned** design.
> **Backend decision (2026-10-24):** the main backend is **Spring Boot** (Java). The ML models in the stack (embeddings, IndicTrans2, Whisper, OCR) and the ingestion tools are Python-only, so they run as a separate internal Python service and a Python batch job, called through the provider interfaces in §6.

## 1. Design goals

1. **Deterministic eligibility.** A json-logic rules engine produces every match result. The LLM only turns results and sourced passages into plain language.
2. **Traceable facts.** Every fact shown to a user comes from a `source` row with `source_url`, `fetched_at` and `verified_at`.
3. **Works on bad networks and cheap phones.** Offline-capable PWA, small payloads, rules can run on the device.
4. **Swappable providers.** LLM, embeddings, translation, speech, OCR and notifications all sit behind interfaces, because free tiers change.
5. **Privacy by default.** Profiles stay on the device unless the user opts in to sync; the server sees coarse bands, not raw identifiers.

## 2. System diagram

```
                         ┌────────────────────────────────────────────────────┐
                         │                  PWA  (web/)                       │
                         │  i18n (10 langs) · STT/TTS (Web Speech API)        │
                         │  on-device profile (IndexedDB, encrypted)          │
                         │  rules engine (json-logic-js) · service worker     │
                         └───────────────┬────────────────────────────────────┘
                                         │ HTTPS / JSON
                                         ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│                         Spring Boot API  (api/, Java 21)                       │
│                                                                                │
│  /schemes   /schemes/{slug}   /rules/bundle   /match   /ask   /checklist       │
│  /reminders   /reports   /volunteer/*   /admin/*  (reviewer UI)                │
│                                                                                │
│  ┌──────────────┐ ┌─────────────────────┐ ┌───────────────┐ ┌───────────────┐  │
│  │ Rules engine │ │ RAG service         │ │ Checklist &   │ │ Notifications │  │
│  │ json-logic-  │ │ retrieve → explain  │ │ reminders     │ │ Web Push,     │  │
│  │ java + trace │ │ → cite → validate   │ │ (@Scheduled)  │ │ Telegram      │  │
│  └──────────────┘ └──┬──────────┬───────┘ └───────────────┘ └───────────────┘  │
│                      │          │                                              │
│              ┌───────▼──────┐ ┌─▼────────────────────────┐                     │
│              │ LLMProvider  │ │ Embedder / Translator /  │                     │
│              │ (Spring AI:  │ │ SpeechToText / TTS / OCR │ ── HTTP (internal) ─┐│
│              │ Groq, Gemini,│ │ client implementations   │                     ││
│              │ Ollama)      │ └──────────────────────────┘                     ││
│              └──────────────┘                                                  ││
└───────────────────┬────────────────────────────────────────────────────────────┘│
                    │ JDBC                                                         │
                    ▼                                                              ▼
┌────────────────────────────────────────────┐   ┌────────────────────────────────────────┐
│ PostgreSQL + pgvector (Supabase or Neon)   │   │ ML service  (ml/, Python, internal)    │
│ schemes · rules · sources · chunks ·       │   │ FastAPI wrapper around local models:   │
│ deadlines · documents · reports · audit    │   │ • /embed      bge-m3 / multilingual-e5 │
│ Flyway migrations · row-level security     │   │ • /translate  IndicTrans2              │
└────────────────────────────────────────────┘   │ • /stt        IndicWhisper / Whisper   │
                    ▲                            │ • /tts        Piper / Indic-TTS        │
                    │                            │ • /ocr        PaddleOCR / Tesseract    │
┌───────────────────┴────────────────────────┐   │ No database access, no public route    │
│ Ingestion + re-check (ingest/, Python)     │   └────────────────────────────────────────┘
│ Playwright + trafilatura, PyMuPDF          │
│ fetch → snapshot+hash → extract → chunk →  │
│ embed (via ML service) → review task       │
│ Runs on GitHub Actions cron                │
└────────────────────────────────────────────┘
```

## 3. Components

| Component | Language | Responsibility | Status |
|---|---|---|---|
| **PWA** (`web/`) | HTML/CSS/JS | UI, i18n, voice, icon tiles; later: on-device profile, client-side rule evaluation, offline cache of published schemes | Prototype |
| **API** (`api/`) | Java 21, Spring Boot 3 | Public API: schemes, matching, assistant, checklist, reminders, reports, volunteer sessions, reviewer admin. Owns all database writes except ingestion snapshots. | Planned |
| **Rules engine** | Java (in `api/`) + JS (in `web/`) | Evaluates json-logic rules against a profile and returns a three-state result with a trace. The same rule JSON runs in json-logic-java and json-logic-js. See [RULES_ENGINE.md](RULES_ENGINE.md). | Planned |
| **RAG service** | Java (in `api/`) | Retrieves verified chunks, asks the LLM to explain, validates citations, attaches the confidence label. See [RAG_ASSISTANT.md](RAG_ASSISTANT.md). | Planned |
| **ML service** (`ml/`) | Python | Stateless HTTP wrapper around local models: embeddings, translation, STT, TTS, OCR. Internal network only; authenticated with a shared service token. | Planned |
| **Ingestion** (`ingest/`) | Python | Fetches official pages and PDFs, extracts text, chunks and embeds, writes `source` snapshots and `chunk` rows, opens review tasks. Also runs the daily re-check. | Planned |
| **Knowledge base** | PostgreSQL + pgvector | Schemes, rules, sources, documents, deadlines, chunks. See [DATA_MODEL.md](DATA_MODEL.md) and [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md). | Planned |
| **Reviewer UI** | Served by `api/` (`/admin`) | Verify sources, encode rules, approve translations, publish | Planned |
| **Volunteer portal** | `api/` + `web/` | Consent-scoped, time-limited assisted sessions with an audit log. See [PRIVACY_AND_SECURITY.md](PRIVACY_AND_SECURITY.md). | Planned (Phase 5) |

**Why two backend languages:** Spring Boot is the maintainer's choice for the product backend. The open-source Indic models (bge-m3, IndicTrans2, IndicWhisper, PaddleOCR) and the extraction tools (trafilatura, PyMuPDF) only have mature Python implementations. Keeping them in a small, stateless Python service means the Java side never depends on them directly, and any of them can be swapped for a hosted API by writing a new Java provider implementation.

## 4. Request flows

### 4.1 "Which schemes may I qualify for?" (`POST /match`)

```
PWA                        API                         DB
 │ profile (coarse bands)   │                           │
 ├─────────────────────────►│ load published rules for  │
 │                          │ state + central ─────────►│
 │                          │◄──────────────────────────┤
 │                          │ evaluate each scheme      │
 │                          │ → likely / not / need_info│
 │                          │   + trace + missing fields│
 │◄─────────────────────────┤ results + next questions  │
```

- The profile can also be evaluated **entirely on the device**: the PWA downloads the published rule bundle (`GET /rules/bundle?state=XX`, versioned and cacheable) and runs json-logic-js. The server-side `/match` exists for clients that can't run JS and for parity tests.
- "Need more info" results return the list of missing profile fields, which drives the adaptive questionnaire's next question.

### 4.2 "Am I eligible for X?" (`POST /ask` with a scheme in context)

1. **Rules:** evaluate X's rules → result + trace.
2. **Embed:** the question is embedded through `Embedder` (ML service `/embed`).
3. **Retrieve:** pgvector search over chunks from X's **published, verified** sources, filtered by language.
4. **Explain:** the LLM gets the trace and the passages only, and writes ≤ 120 words at grade-5 level in the user's language, citing passages by id.
5. **Validate:** every cited id must be in the retrieved set; banned phrases are rejected; if no passage scored above the threshold, the answer becomes the "no official source" response.
6. **Respond:** answer text, result from step 1 (never from the LLM), confidence label, citations (`source_url`, `published_date`, `verified_at`), staleness warning if any, disclaimer.

### 4.3 Free question (`POST /ask` without a scheme)

As 4.2 without step 1, retrieving across all published sources. If the question is about eligibility, the answer points the user to the questionnaire instead of judging.

### 4.4 Source re-check (daily, `ingest/` on GitHub Actions cron)

```
for each source with scheme.status = published:
    fetch (respect robots.txt, rate limit) → normalise text → sha256
    if hash != source.content_hash:
        scheme.status = needs_review; open review_task(reason = source_changed)
    if now - source.verified_at > scheme.reverify_days:
        open review_task(reason = reverify_due) → users see the "may be outdated" warning
```

### 4.5 Reminders (hourly, Spring `@Scheduled` + ShedLock)

Find deadlines closing within the user's chosen window (default 14 and 3 days) for schemes the user saved, plus unfinished checklist items, and send through the user's opted-in channel. ShedLock makes sure only one API instance sends. Reminders contain no personal data beyond the scheme name and date.

## 5. API surface (planned)

| Method & path | Purpose |
|---|---|
| `GET /schemes?state=&category=&lang=` | List published schemes |
| `GET /schemes/{slug}?lang=` | Scheme detail: summary, documents, deadlines, apply link, sources |
| `GET /rules/bundle?state=` | Versioned bundle of published rules for on-device matching |
| `POST /match` | Three-state results for a profile (or household) |
| `POST /ask` | Cited assistant answer |
| `POST /checklist` | De-duplicated document list for selected schemes, with how-to-obtain per state |
| `POST /reminders` / `DELETE /reminders/{id}` | Opt in / out of reminders |
| `POST /reports` | "Report outdated info" |
| `POST /stt`, `POST /tts` | Server speech fallback (proxied to the ML service) |
| `/volunteer/*` | Session codes, scoped profile view, audit log (Phase 5) |
| `/admin/*` | Reviewer workflow (authenticated, role-checked) |

The OpenAPI spec is generated by springdoc-openapi at `/v3/api-docs`. All responses carrying scheme facts include `sources[]` with `url`, `published_date`, `fetched_at`, `verified_at` and a `stale` flag.

## 6. Provider interfaces

Each external dependency is a small Java interface with one implementation per provider, selected by configuration (`jansathi.providers.*` in `application.yml`, wired with `@ConditionalOnProperty`). Tests use fake implementations.

```java
public interface LlmProvider {
    Completion complete(String system, List<Message> messages, CompletionOptions options);
    // options: maxTokens, temperature (default 0), jsonSchema (nullable)
}

public interface Embedder {
    String modelId();   // stored with each chunk; re-embed when it changes
    int dimension();
    List<float[]> embed(List<String> texts, EmbedKind kind);   // QUERY or PASSAGE
}

public interface Translator {
    List<Translation> translate(List<String> texts, String srcLang, String tgtLang);
}

public interface SpeechToText {
    Transcript transcribe(byte[] audio, String lang);
}

public interface TextToSpeech {
    byte[] synthesize(String text, String lang);   // audio/ogg
}

public interface OcrProvider {
    OcrResult read(byte[] image, List<String> langs);
}

public interface Notifier {
    Channel channel();   // WEBPUSH, TELEGRAM, SMS, WHATSAPP
    DeliveryResult send(String recipient, LocalisedMessage message);
}
```

| Interface | Default (MVP) | Alternatives |
|---|---|---|
| `LlmProvider` | Groq free tier via Spring AI's OpenAI-compatible client | Gemini, local Ollama (Llama / Qwen) via Spring AI |
| `Embedder` | ML service → `BAAI/bge-m3` | ML service → `intfloat/multilingual-e5` |
| `Translator` | ML service → IndicTrans2 | — |
| `SpeechToText` | Browser Web Speech API (client) | ML service → IndicWhisper / Whisper |
| `TextToSpeech` | Browser `speechSynthesis` (client) | ML service → Piper, AI4Bharat Indic-TTS |
| `OcrProvider` | ML service → PaddleOCR | ML service → Tesseract (+ Indic packs), docTR |
| `Notifier` | Web Push (`nl.martijndwars:web-push` or equivalent) | Telegram Bot API; WhatsApp Cloud API / SMS later |

Free-tier quotas change. Check current limits before relying on one.

The ML service's own API is versioned (`/v1/embed`, …) and documented in `ml/README.md`. The Java client implementations set timeouts and circuit breakers (Resilience4j), so a slow model degrades a feature (e.g. no server TTS) instead of failing the request.

## 7. Backend stack (Spring Boot)

| Concern | Choice |
|---|---|
| Runtime | Java 21 (LTS), Spring Boot 3.x |
| Build | Gradle (Kotlin DSL) |
| Web | Spring Web MVC, Bean Validation (`jakarta.validation`) on request records |
| Data | Spring Data JDBC (or JPA) on PostgreSQL; pgvector via the `pgvector-java` type or Spring AI `PgVectorStore` |
| Migrations | Flyway (`api/src/main/resources/db/migration`) |
| Rules | `json-logic-java` (`io.github.jamsesso`) |
| LLM | Spring AI (OpenAI-compatible, Ollama, Gemini) behind `LlmProvider` |
| Security | Spring Security: anonymous public API; OAuth2/OIDC or passkeys for reviewers and volunteers; method-level role checks |
| Scheduling | `@Scheduled` + ShedLock |
| Resilience | Resilience4j (timeouts, retries, circuit breakers) for providers |
| API docs | springdoc-openapi |
| Observability | Spring Boot Actuator, Micrometer → Grafana Cloud; Sentry SDK with PII scrubbing |
| Tests | JUnit 5, AssertJ, Testcontainers (Postgres + pgvector), WireMock for the ML service and LLM |

Base package: `org.jansathi` (`in.jansathi` is not allowed because `in` is a Java keyword).

## 8. Deployment (planned)

| Piece | Where |
|---|---|
| PWA | Cloudflare Pages or Vercel free tier |
| API | Container host (e.g. Fly.io, Render, Railway, or a small VPS); ~512 MB–1 GB RAM. Optionally a GraalVM native image to cut memory and cold starts. |
| ML service | Separate container with ≥ 4 GB RAM (more for Whisper/IndicTrans2); can scale to zero and start only when needed, since browser speech is the default |
| Database | Supabase (Postgres + pgvector + RLS) or Neon |
| Ingestion / re-check | GitHub Actions cron |
| Monitoring | Sentry, Grafana Cloud free tiers; no personal data in logs |

## 9. Planned repo layout

```
web/        PWA (moves to SvelteKit or Next.js when routing/state are needed; same locale JSON)
api/        Spring Boot app (Gradle)
  src/main/java/org/jansathi/
    scheme/  rules/  rag/  checklist/  reminder/  volunteer/  admin/  provider/
  src/main/resources/
    application.yml
    db/migration/          Flyway SQL
    prompts/               versioned LLM prompts
ml/         Python ML service (embeddings, translation, STT, TTS, OCR)
ingest/     Python fetchers, extractors, chunker, re-check job
rules/      shared rule fixtures (YAML), run by both the Java and JS engines
eval/       RAG evaluation set and runner
docs/       these docs
```
