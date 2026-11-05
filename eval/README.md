# Assistant evaluation

`rag/en.jsonl` and `rag/hi.jsonl` hold questions for the assistant ([docs/RAG_ASSISTANT.md §7](../docs/RAG_ASSISTANT.md#7-evaluation)). One JSON object per line:

```json
{ "id": "en-006", "lang": "en", "type": "eligibility", "question": "Who can get the widow pension?", "expected_schemes": ["ignwps"], "split": "dev" }
```

- `expected_schemes`: slugs whose official rule passages answer the question; any one of them counts. Empty means the question is **unanswerable** from our sources, and the only correct answer is "I couldn't find an official source for this".
- `split`: `dev` or `heldout`, set by `python eval/split.py`, stratified by language, answerable or not, and batch. **Tune on dev only.** New items get a split when you rerun the script; existing marks never move.
- Keyword mode: `npm run test:backend` (`RetrievalEvalTest` prints hit and correct-refusal rates and fails below the thresholds in that file). CI runs it on every push.
- With embeddings, against a running ML service (`EmbeddingEvalTest`, skipped otherwise):

```bash
JANSATHI_EVAL_ML_URL=http://127.0.0.1:8090 node tools/gradle.mjs test --tests '*EmbeddingEvalTest' -i --rerun
JANSATHI_EVAL_SWEEP=1 ...   # the same with a MIN_SIMILARITY sweep, on dev only
```

**Results (2026-11-05, 49 schemes including drafts, 206 items, multilingual-e5-small, `MIN_SIMILARITY` 0.83):**

| Split | Language | Keyword: hit | Keyword: refusal | Fused: hit | Fused: refusal |
|---|---|---|---|---|---|
| dev | en | 35/42 (83%) | 11/11 | 40/42 (95%) | 11/11 |
| dev | hi | 36/42 (86%) | 11/11 | 41/42 (98%) | 11/11 |
| **held-out** | en | 37/40 (93%) | 10/10 | 38/40 (95%) | 10/10 |
| **held-out** | hi | 37/40 (93%) | 10/10 | 37/40 (93%) | 10/10 |

Read the held-out rows: in English and Hindi fusion gains **one** question out of 80, against ten on dev, where the threshold was tuned. The keyword column is flattered: its gates were last adjusted on 2026-11-05 looking at all 206 items, including held-out ones, because the new schemes had pushed its English refusal rate to 86%, below the test's 90%.

**In the other eight languages the difference is total.** English held-out questions, machine translated by `eval/translate_heldout.py` into `rag-translated/` (the translator refused 2 and 6 of 50; passages stay in English):

| Language | Keyword: hit | Keyword: refusal | Fused: hit | Fused: refusal |
|---|---|---|---|---|
| ta | 0/38 | 10/10 | 28/38 (74%) | 10/10 |
| bn | 0/34 | 10/10 | 28/34 (82%) | 10/10 |

Keywords can't match Tamil against English text, so they refuse everything. These are machine-translated questions, not real ones, so take them as a direction, not a score. This is the main reason to run the embedder in production.

**The threshold is fragile.** e5 similarities sit in a narrow band: unrelated questions top out near 0.82, answerable ones usually above 0.84. On the 28 schemes of 2026-11-04 the best value was 0.825; at 49 schemes it was 0.83, because more passages mean a higher chance that something unrelated scores well. **Rerun the sweep whenever many schemes are added.** Between 0.825 and 0.84 the dev numbers trade hits for refusals; 0.83 is the lowest value with no wrong answer on dev.

Other findings:

- Embedding the scheme name with each rule mattered more than anything else: with vectors alone, dev hit went from 83% to 98% in English and from 60% to 98% in Hindi ("Age 18 to 40" says nothing by itself).
- With an embedder on, a question also needs something close in meaning, so keyword overlap alone no longer answers ("a subsidy for a new car"). That is what lets fused mode refuse well.
- Misses on the last full run include `en-029`, `en-078`, `hi-054`, `hi-057`, `hi-070`, `hi-083`; `en-069` found rooftop solar for a kutcha house. Only rule passages are searched; summaries and benefits are not.

**Known limits.** All 206 items were written by AI sessions with access to the scheme content, so the held-out split is held out from tuning, not from the people who wrote the system; the first 75 items were also used to tune keyword retrieval. Questions from someone who hasn't seen this code, ideally real user questions, are still needed. There are only 20 unanswerable held-out items: one question is 5 points. Answer metrics (hallucination, readability) need a configured LLM and are not run yet.
