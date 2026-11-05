# Machine translations of scheme content

`<lang>.json` for bn, ta, te, mr, gu, kn, ml, pa: scheme names, summaries, benefits, how to apply, rule text, and document names, steps and offices. **Machine translated. Nothing here has been read by a native speaker.** These files are not part of the reviewed scheme files and do not go through `tools/publish.mjs`: they change no rule, and the app shows a "machine translated" note. English and Hindi live in the scheme files.

```json
{
  "model": "sarvamai/sarvam-translate",
  "schemes": { "<slug>": { "name": "", "summary": "", "benefit": "", "how_to_apply": "", "rules": { "<code>": "" } } },
  "documents": { "<code>": { "name": "", "how_to": "", "office": "" } },
  "source_hash": { "schemes.<slug>.name": "sha256:…", "schemes.<slug>.rules.<code>": "sha256:…", "documents.<code>.how_to": "sha256:…" }
}
```

`source_hash` holds the SHA-256 of each field's English text (UTF-8). A field whose hash no longer matches is stale. A missing field means the app shows English.

## Regenerating

```bash
docker compose --profile ml --profile translate up -d ml llama
node tools/translate-content.mjs                 # published schemes and documents
node tools/translate-content.mjs --all           # also drafts and schemes waiting for review
node tools/translate-content.mjs --check         # what is current, due or unsafe; changes nothing
node tools/translate-content.mjs --drop-stale    # only remove stale fields; no service needed
```

Only fields that are new, changed or missing are sent, so a rerun after publishing costs only what changed. The run saves after every batch and can stop and resume. A full language (about 400 fields) takes about 45 minutes on 6 shared CPU cores; `--lang ta` does one.

## What protects the text

1. Text is translated sentence by sentence. URLs and acronyms (PM-KISAN, CSC, UDID) are swapped for `<0>` markers and put back, because left alone the model writes them in the target script.
2. **Numbers:** every number must appear in the output, compared after removing grouping commas and mapping Indic digits to 0-9. A sentence that differs is retried twice, then the whole field stays in English. The check runs in the service and again in the tool, and again on every kept field at each run.
3. Each marker must come back exactly once; empty, copied or runaway output is rejected.
4. Banned phrases (`tools/banned-phrases.json`) are checked in English and the target language. `npm run lint:copy` also covers these files.

The output may use the language's own digits (Bengali ৬০০০) and may write ₹6,000 as "6,000 rupees" in words: the value is checked, the form is not.

## Model and licence

**`sarvamai/sarvam-translate`**: a Gemma 3 4B fine-tune, licence **GPL-3.0**, run as the third-party quantisation `Mungert/sarvam-translate-GGUF` (`q4_k_m`, 2.4 GB) on llama.cpp. Gemma's own terms may also apply; check them with a lawyer before commercial use. The GGUF has no chat template, so `ml/backends.py` writes the prompt itself.

The other candidates are **NLLB-200 distilled, CC-BY-NC 4.0: non-commercial.** It is off by default and nothing in these files came from it. `ML_TRANSLATE_MODEL=nllb-600m` still works. IndicTrans2 (MIT) is selectable with `HF_TOKEN` but is gated and has **not been tried**; it may well be the better choice, and its licence is the simplest.

## How the model was chosen

Same sample for each: 30 scheme strings (en to hi, against the reviewed Hindi) and 15 UI strings per language (against `frontend/public/locales`, themselves machine-authored, so only a rough reference). chrF, higher is better, on a shared CPU, 2026-11-05.

| | hi | bn | ta | te | mr | gu | kn | ml | pa | Sentences with right numbers | Time per language |
|---|---|---|---|---|---|---|---|---|---|---|---|
| NLLB 600M int8 | 56.0 | 55.4 | 66.8 | 59.1 | 56.4 | 56.1 | 66.1 | 63.0 | 52.8 | 226/231 | 26 s |
| NLLB 1.3B int8 | 58.4 | 52.5 | 65.4 | 58.3 | 56.1 | 58.4 | 68.0 | 60.4 | 51.7 | 223/223 | 33 s |
| **Sarvam-Translate q4** | 56.2 | **60.2** | **69.1** | **69.3** | **62.2** | **62.6** | 63.7 | **66.3** | **59.1** | 231/231 | 115 s |

Sarvam is about four times slower and better in seven of the eight target languages. The numbers undersell the difference in meaning. NLLB turned "the person asking for work" into "asking for a job", dropped "up to" from "up to 82,000 scholarships", dropped "at any level", and lost a whole instalment amount in Bengali. And its licence is the more restrictive one.

Problems seen in Sarvam's output, all worth a reviewer's eye:

- Fragments become commands: "Help to buy aids" came out in Bengali as "please help buy".
- "tapping" became "typing" in Tamil.
- Plain words go formal: "disability" as "இயலாமை" where the app uses "மாற்றுத்திறன்"; there is no term list yet, so one word can vary between schemes.
- Scheme names not in the mask (Saathi, Jan Dhan) are transliterated, not always the same way.

The UI strings and these files were written separately, so their terms may differ.
