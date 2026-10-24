# RAG assistant

> **Status:** planned (Phase 3). Not implemented. MVP languages for answers: Hindi and English.

The assistant explains schemes in plain language, using **only** passages retrieved from the verified knowledge base, and cites them. It never decides eligibility (the [rules engine](RULES_ENGINE.md) does) and never answers from model memory.

## 1. Pipeline

```
question (text or speech → text)
   │
   ▼
1. Normalise ── detect language, transliteration check (Hinglish), strip personal numbers
   │
   ▼
2. Classify ── scheme-specific? eligibility? process/documents? deadline? off-topic?
   │
   ├─ eligibility question ──► rules engine result + trace (deterministic)
   ▼
3. Retrieve ── pgvector top-k over chunks of PUBLISHED sources
   │            filters: scheme (if known), state + central, lang
   ▼
4. Gate ────── no chunk above threshold? ──► "no official source" response (no LLM call)
   │
   ▼
5. Explain ─── LLM with the prompt contract (§3), JSON output
   │
   ▼
6. Validate ── citations ⊆ retrieved ids · banned phrases · result not altered · length
   │            fail → one retry → else "no official source" response
   ▼
7. Respond ─── answer + label + citations + stale warnings + disclaimer (+ TTS)
```

## 2. Retrieval

- **Embedder:** `BAAI/bge-m3` (default) or `intfloat/multilingual-e5`, run locally behind the `Embedder` interface. Queries and passages are embedded in the same multilingual space, so a Hindi question can match an English guideline.
- **Search:** cosine similarity, top-k = 8, then keep passages scoring ≥ the threshold (tuned on the evaluation set; start at 0.55 for bge-m3). Combine with a keyword (Postgres full-text) score for scheme names and numbers, and merge the two lists (reciprocal-rank fusion).
- **Filters:** only chunks whose source belongs to a `published` scheme (or `needs_review`, which forces the uncertain label). State filter = user's state + central.
- **Diversity:** at most 3 chunks per source, so one long PDF can't crowd out the others.
- **Language:** prefer chunks in the user's language when they're reviewed translations; otherwise use the original-language chunk and let the LLM explain in the user's language.

## 3. Prompt contract

The system prompt is versioned in `api/src/main/resources/prompts/assistant.vN.txt` and covered by the copy lint. Its contract:

1. **Answer only from the passages provided.** Do not use outside knowledge, even if you think you know the answer.
2. **Cite every factual sentence** with the passage id(s) it comes from, e.g. `[p3]`.
3. If the passages don't answer the question, return `"status": "not_found"`. Do not guess.
4. **Never state or imply that the user is eligible, will receive a benefit, or will be approved.** If a rules-engine result is provided, describe it using its label ("you may be eligible", "we need more information", "this scheme may not fit because…") and do not contradict it.
5. Write in `{lang}`, at a grade-5 reading level: short sentences, common words, no legal jargon. ≤ 120 words.
6. Numbers, amounts and dates must appear exactly as in the passages.
7. Don't ask for or repeat Aadhaar numbers, bank details or other identifiers.
8. Output JSON matching the schema in §4. No text outside the JSON.

The user message contains: the question, the language, the rules-engine result and trace (if any), and the passages as `{id, text, title, locator}`. The model sees **no** user profile fields beyond what's needed to explain the trace.

Generation settings: temperature 0–0.2, JSON mode / schema-constrained output where the provider supports it.

## 4. Answer schema

LLM output (parsed into a Java record and validated with Bean Validation plus the JSON Schema passed to the model):

```json
{
  "status": "answered",                // "answered" | "not_found"
  "answer": "आप 60 साल या उससे ज़्यादा उम्र के हैं, तो यह योजना आपके लिए हो सकती है [p1]। …",
  "citations": ["p1", "p3"],
  "follow_up": "क्या आपके परिवार के पास बीपीएल राशन कार्ड है?"
}
```

API response to the client (assembled by the server, not the LLM):

```json
{
  "status": "answered",
  "lang": "hi",
  "answer": "…",
  "label": "confirmed",                 // weakest label among cited sources
  "match": { "scheme": "ignoaps", "result": "NEED_MORE_INFO", "missing_fields": ["is_bpl"] },
  "citations": [
    { "id": "p1", "source_id": "…", "title": "…", "publisher": "…", "url": "https://…",
      "locator": "p. 3", "published_date": "…", "verified_at": "2026-10-12", "stale": false }
  ],
  "warnings": [],                        // e.g. ["stale_source"]
  "disclaimer_key": "disclaimer.not_government",
  "prompt_version": "assistant.v3",
  "model": "…"
}
```

### The "not found" response

Returned when retrieval finds nothing above the threshold, the LLM returns `not_found`, or validation fails twice:

> *"I couldn't find an official source for this. You can check the official portal, ask at your nearest CSC, or tap 'Report missing info'."*

It is a localised template (`assistant.not_found`), not model output, and always offers the nearest-help link.

## 5. Validation (step 6)

| Check | On failure |
|---|---|
| JSON parses and matches the schema | retry once |
| Every `[pN]` in the answer is in `citations`, and every citation was retrieved | retry once, then not_found |
| At least one citation when `status = answered` | not_found |
| No banned phrases (same list as the copy lint, in all 10 languages) | retry once, then not_found |
| `match.result` comes from the rules engine; answer text doesn't contradict it | replace with the templated result explanation |
| Length ≤ 120 words (soft), ≤ 180 (hard) | truncate at a sentence boundary |

## 6. Safety and privacy

- Personal numbers (12-digit Aadhaar patterns, bank account / IFSC patterns, phone numbers) are stripped from the question before logging, retrieval and LLM calls.
- Logs store the question hash, retrieved ids, output and timings, not raw questions, unless the user opts in to "help improve answers".
- Off-topic or harmful requests get a short templated refusal and a pointer back to scheme help.
- Model and prompt versions are recorded with every answer so regressions can be traced.

## 7. Evaluation

**Dataset:** `eval/rag/{en,hi}.jsonl`, at least 100 items per language at launch:

```json
{ "id": "hi-042", "lang": "hi", "question": "पीएम किसान की किस्त साल में कितनी बार आती है?",
  "expected_answer": "साल में तीन किस्तें", "expected_sources": ["<source_id>"],
  "type": "factual" }
```

Item types: factual, process/documents, deadline, eligibility (paired with a profile), **unanswerable** (≥ 20%, to test "no source, no answer"), and adversarial (asks for a guarantee, asks about a non-existent scheme, asks to ignore instructions).

**Metrics** (run in CI on every change to prompts, retrieval or provider config):

| Metric | Definition | Target (MVP) |
|---|---|---|
| Citation accuracy | answered items whose citations include an expected source | ≥ 90% |
| Correct refusal rate | unanswerable items that return not_found | ≥ 95% |
| False refusal rate | answerable items that return not_found | ≤ 10% |
| Hallucination rate | answered items with a claim not supported by the cited passages (LLM judge + human spot check of 20%) | ≤ 2% |
| Banned-phrase rate | answers containing a banned phrase | 0 |
| Readability | grade level of answers (per-language heuristics) | ≤ grade 6 |

CI fails if a metric drops below target or regresses by more than 2 percentage points from `main`.
