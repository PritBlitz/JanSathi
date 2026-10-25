# Assistant evaluation

`rag/en.jsonl` and `rag/hi.jsonl` hold questions for the assistant ([docs/RAG_ASSISTANT.md §7](../docs/RAG_ASSISTANT.md#7-evaluation)). One JSON object per line:

```json
{ "id": "en-006", "lang": "en", "type": "eligibility", "question": "Who can get the widow pension?", "expected_schemes": ["ignwps"] }
```

- `expected_schemes`: slugs whose official rule passages answer the question; any one of them counts. Empty means the question is **unanswerable** from our sources, and the only correct answer is "I couldn't find an official source for this".
- Run: `npm run test:backend` (the `RetrievalEvalTest` prints hit and correct-refusal rates and fails below the thresholds in that file). CI runs it on every push.

**Known limits.** 70 items (45 en, 25 hi), against the 100+ per language the design asks for. Retrieval was tuned while looking at these items, so the scores overstate real quality. New items should be written by someone who has not seen the retrieval code, ideally from real user questions, and kept separate as a held-out set. Answer metrics (hallucination, readability) need a configured LLM and are not run yet.
