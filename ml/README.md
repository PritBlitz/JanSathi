# ML service

Internal Python service for models that only have good Python implementations ([ARCHITECTURE.md](../docs/ARCHITECTURE.md)). Keep it on a private network; it is not for the public internet. Everything is off by default in the API: with the service down or unset, retrieval uses keywords and the app uses browser speech.

**Status (2026-11-05): runs in Docker with real models** on a 6-core CPU with no GPU. No torch in the image: ONNX Runtime and CTranslate2 run everything. Measured results are below. OCR is not here; the document check runs in the browser.

| Endpoint | Model | Licence of the weights |
|---|---|---|
| `POST /v1/embed` | `intfloat/multilingual-e5-small`, quantised ONNX from `Xenova/multilingual-e5-small` | MIT |
| `POST /v1/tts` | `facebook/mms-tts-{eng,hin,ben,tam,tel,mar,guj,kan,mal,pan}`, exported to ONNX | **CC-BY-NC 4.0, non-commercial** |
| `POST /v1/stt` | `Systran/faster-whisper-small` (Whisper, int8) | MIT |
| `POST /v1/translate` | `sarvamai/sarvam-translate` (default), NLLB-200 distilled, IndicTrans2 | GPL-3.0; **NLLB CC-BY-NC 4.0**; IndicTrans2 MIT |

**The MMS voices are non-commercial.** They may not be used in a commercial service. If JanSathi is ever offered commercially, or funded in a way the licence does not cover, server speech needs other voices. Ask a lawyer; this table is not legal advice.

## Run

```bash
docker compose --profile ml-export run --rm ml-export   # once: make the voices (needs about 2 GB of image, 1.1 GB of output)
docker compose --profile ml up -d --build ml            # http://127.0.0.1:8090
python -I ml/test_service.py                            # tests, no packages or models needed
python ml/roundtrip.py --langs hi --n 5 --show          # synthesise and transcribe back, needs the service
```

Weights go to `ML_MODELS_DIR` (default `ml/.models`, git-ignored), fetched on first use. **Models load on first use** and the embedder, Whisper and up to `ML_TTS_KEEP` voices then stay in memory. Without a model an endpoint answers 503; it never returns made-up vectors, audio or text.

Then point the API at it: `JANSATHI_EMBED_PROVIDER=ml-service` and `JANSATHI_SPEECH_PROVIDER=ml-service`, with the base URLs (compose fills these in) and `ML_SERVICE_TOKEN`. **Recommended for production:** the embedder (see [eval/README.md](../eval/README.md)).

| Variable | Default | |
|---|---|---|
| `ML_MODELS_DIR` | `ml/.models` | Weights. In compose, the folder mounted at `/models`; on Linux it must be writable by uid 10002 |
| `ML_SERVICE_TOKEN` | none | Shared bearer token; set it unless the network is private |
| `ML_THREADS` | 4 | CPU threads per model |
| `ML_MAX_CONCURRENT` | 2 | Speech and translation requests at once. Embedding is never queued behind them |
| `ML_TTS_KEEP` | 3 | Voices kept loaded (about 110 MB on disk each) |
| `ML_STT_MODEL` | `small` | `base` or `small`, see below |
| `ML_STT_BEAM` | 1 | Beam size |
| `ML_STT_LANGS` | `en,hi,ta` | The languages `/v1/status` offers and `/v1/stt` accepts; see the round trip below |
| `ML_TRANSLATE_MODEL` | `sarvam` | `sarvam`, `nllb-600m`, `nllb-1.3b`, `indictrans2` |
| `ML_LLAMA_URL` | `http://llama:8091` | llama.cpp server for Sarvam: `docker compose --profile translate up -d llama` |
| `HF_TOKEN` | none | Only for gated models. Needed for IndicTrans2 |

## API

All except `/healthz` need the token when one is set. Request bodies may have a length or be chunked (the JDK client sends chunked).

- `GET /healthz`: `{"status":"ok"}`.
- `GET /v1/status`: `{"tts":[lang…],"stt":[lang…],"models":{…}}`. `tts` lists voices that exist on disk.
- `POST /v1/embed` with `{"texts":["…"],"kind":"query"|"passage"}` (1 to 256 texts): `{"vectors":[[…]]}`, unit length. The `query: ` / `passage: ` prefixes e5 was trained with are added here.
- `POST /v1/tts` with `{"text":"…","lang":"hi"}` (up to 2000 characters): `audio/wav`, 16 kHz mono. MMS voices have no digits or symbols, so numbers, ₹ and % are spelt out first (`textguard.for_speech`): as words in Hindi, English and Bengali, digit by digit elsewhere, which is clumsy but never wrong. URLs are skipped.
- `POST /v1/stt?lang=hi` with the raw recording (WAV, WebM, Ogg, MP4; up to 10 MB and 60 seconds): `{"text":"…"}`. 422 if it cannot be decoded.
- `POST /v1/translate` with `{"texts":["…"],"source":"en","target":"ta"}`: `{"model":"…","translations":["…"|null]}`. Text is split into sentences; URLs and acronyms are swapped for `<0>` markers and put back; a sentence whose numbers differ from the source, or which lost a term, is retried twice. `null` means a sentence never passed, so the text must stay in English.

Nothing is logged and nothing is written to disk: audio is decoded from memory, and requests live only as long as they run. The API removes runs of 9+ digits from questions before embedding them.

## Measured

Docker on 6 cores (12 threads), no GPU, with other builds sharing the machine, so treat times as upper bounds. Image: 794 MB (`Dockerfile.export`, used once, is 1.9 GB).

| | Cold | Warm | Memory of the container |
|---|---|---|---|
| Embedding | about 5 s, first request | 27 ms per question; `/api/v1/ask` in all 40 to 100 ms | 460 MB |
| TTS | 12 s for the first sentence of a language | 2 s for a 7 second clip | with Whisper and 3 voices: 1.9 GB peak |
| STT, Whisper small | about 15 s | 0.65x real time in Hindi: 4.3 s for 6.6 s of audio | same |
| STT, Whisper base | | 0.23x real time | 980 MB |

Round trip through the service, 5 sentences per language, character error rate:

| | en | ta | hi | mr | ml | pa | kn | te | gu | bn |
|---|---|---|---|---|---|---|---|---|---|---|
| Whisper small | 0.06 | 0.12 | 0.19 | 0.74 | 0.99 | 1.27 | 1.39 | 1.45 | 1.46 | 1.62 |
| Whisper base | 0.12 | 0.18 | 0.85 | | | | | | | |

Small is the smallest size usable in Hindi, and it keeps up with real time on this CPU. The voice is synthetic, so these numbers compare languages and prove the pipeline; they are not accuracy on real speech. See docs/I18N_AND_VOICE.md §5.3.

Translation candidates are compared in [content/translations/README.md](../content/translations/README.md).

IndicTrans2 is wired (`ML_TRANSLATE_MODEL=indictrans2`, `HF_TOKEN`, `pip install -r ml/requirements-indictrans2.txt`, which adds torch) but **has never been run**: it is gated and no token was available. Its MIT licence and strong Indic results make it worth trying.
