# Languages and voice

> **Status:** the UI i18n and browser speech in §1–§5.2 are built (`frontend/src/lib/i18n.js` and its React provider `i18n-context.jsx`, `frontend/src/lib/speech.js` and its hooks `use-speech.js`), with all 10 locale files and `frontend/public/locales/review-status.json`; `npm run check:locales` checks key parity. The 9 non-English locales are machine-authored and unreviewed; none holds English placeholders today (`untranslated_keys` in `review-status.json` are empty). Server speech (`/api/v1/speech/*`, off by default) and machine translation of scheme content (`content/translations/`) are built and measured (§5.3, §6); the app's use of server speech is up to the frontend. Review tooling for translations is planned.

## 1. Supported languages

| Code | Language | Speech tag | Script | Noto family |
|---|---|---|---|---|
| `en` | English | `en-IN` | Latin | Noto Sans |
| `hi` | Hindi | `hi-IN` | Devanagari | Noto Sans Devanagari |
| `bn` | Bengali | `bn-IN` | Bengali | Noto Sans Bengali |
| `ta` | Tamil | `ta-IN` | Tamil | Noto Sans Tamil |
| `te` | Telugu | `te-IN` | Telugu | Noto Sans Telugu |
| `mr` | Marathi | `mr-IN` | Devanagari | Noto Sans Devanagari |
| `gu` | Gujarati | `gu-IN` | Gujarati | Noto Sans Gujarati |
| `kn` | Kannada | `kn-IN` | Kannada | Noto Sans Kannada |
| `ml` | Malayalam | `ml-IN` | Malayalam | Noto Sans Malayalam |
| `pa` | Punjabi | `pa-IN` | Gurmukhi | Noto Sans Gurmukhi |

The list lives in `LANGS` in `frontend/src/lib/i18n.js`.

## 2. How UI i18n works

**Using strings in components** (React, since 2026-10-25)

```jsx
const { t, formatNumber } = useI18n();
<h2>{t('tiles.title')}</h2>
<input placeholder={t('search.placeholder')} />
<button aria-label={t('ask.close')}><Icon name="close" /></button>
<p>{t('tiles.count', { n: formatNumber(12) })}</p>
```

- `t(key, vars)` returns the string for the current language and fills `{placeholders}`. Text and attributes (placeholder, aria-label, title) all go through it.
- Changing the language re-renders the app from the `I18nProvider`; nothing is patched in the DOM.
- The boot screen in `index.html` shows only the brand name; there is no other UI text in HTML.

**Locale files:** `frontend/public/locales/<code>.json`, one per language, with **identical key sets**. Keys are dotted, grouped by area (`hero.*`, `tiles.*`, `ask.*`, `speech.*`, …).

**Loading and fallback**

1. On first visit the language is picked from `navigator.languages` (first supported match), else English.
2. The choice is saved in `localStorage` and reused next time. If storage is unavailable (private mode), the app still works and just re-detects.
3. The locale JSON (and English, the fallback) is fetched before the app renders.
4. Any key missing from the chosen locale falls back to `en.json`.
5. `<html lang>` is set to the language code and the page title is updated from the locale.

**Formatting:** dates, numbers and currency go through `Intl.DateTimeFormat` / `Intl.NumberFormat` with the `xx-IN` tag (e.g. ₹2,50,000 in Indian grouping). Don't concatenate translated fragments; use whole sentences with `{placeholders}`.

## 3. Adding a key

1. Add the key and English text to `en.json`.
2. Add the same key to **all nine** other locale files. If you can't translate it, copy the English and add the key to that locale's `untranslated_keys` in `review-status.json`, so it's queued for translation. (CI will check key parity; see [CONTRIBUTING.md](CONTRIBUTING.md).)
3. Use it via `t('key')`. Never put user-facing text directly in JSX, HTML or JS.
4. Run the copy rules on the English text: no "you are eligible", "you will get", "guaranteed".
5. Check the layout with the longest translation (usually Tamil or Malayalam).

## 4. Adding a language

1. Add an entry to `LANGS` in `frontend/src/lib/i18n.js` (code, native name, speech tag).
2. Create `frontend/public/locales/<code>.json` with every key from `en.json`.
3. Add the Noto font for the script (see [DESIGN.md](../DESIGN.md#4-typography)) and check line-height.
4. Test speech: is there a browser voice for the tag on Android Chrome and desktop Chrome? Record the result in the voice coverage table below.
5. Get a native-speaker review (§6) before it's shown by default.

## 5. Voice

### 5.1 Text-to-speech (`frontend/src/lib/speech.js`)

Uses the browser `speechSynthesis` API through `speak`, `stop` and `hasVoice`.

- **What can be read:** the whole page ("Listen" in the nav), a single section (speaker button on the section), or a tile's name when it's tapped.
- **Voice choice:** the first voice whose `lang` matches the speech tag, then any voice for the base language.
- **No voice for the language:** `hasVoice` returns false and the app shows a localised notice that this device has no voice for the language. It does **not** read the text in a different language's voice.
- Speech stops when the user changes language, leaves the page, or taps stop.
- Rate is slightly slower than default for clarity; users can change it later in settings (planned).

### 5.2 Speech-to-text

Uses the browser `SpeechRecognition` (`webkitSpeechRecognition`) through `listen`, with `lang` set to the speech tag.

- Works best in Chrome and Android Chrome. Firefox and some other browsers don't support it.
- If it's unsupported or permission is denied, the mic button explains why and the text box stays available. **Typing is always possible.**
- A visible "Listening…" state and the live transcript are shown, and both are announced to screen readers.

### 5.3 Voice coverage and fallbacks

Browser voices vary by device, and several Indic languages have no voice on desktop browsers. Fallbacks:

| Gap | Fallback | Status |
|---|---|---|
| No browser TTS voice | Server voices: `POST /api/v1/speech/tts`, MMS through `ml/`, all 10 languages | Built, off by default. **MMS is CC-BY-NC: non-commercial** |
| No browser speech recognition | `POST /api/v1/speech/stt?lang=hi`, Whisper small through `ml/` | Built, off by default. **English, Hindi and Tamil only** |
| Offline | Pre-generated audio for fixed UI strings, cached by the service worker | Planned |

The client picks: browser first, then server (if online and the user allows sending audio), then text only.

**API** (no sign-in needed; 20 requests a minute per address, 2 running at once):

- `GET /api/v1/speech/status`: `{"tts":["en","hi",…],"stt":["en","hi","ta"]}`. Both empty when speech is off or the service is down, so the app knows to stay on browser speech.
- `POST /api/v1/speech/tts` with `{"text":"…","lang":"hi"}`, up to 1000 characters: `audio/wav`.
- `POST /api/v1/speech/stt?lang=hi` with the recording as the raw body (`audio/*`, up to 5 MB, 60 seconds): `{"text":"…"}`.

Errors are problem+json: 400 bad input, 413 too large, 415 not audio, 429 over the limit, 503 off, down or busy. A 503 means fall back, not retry. Audio and text pass through memory only: they are never stored or logged, and responses are `no-store`.

**Why three languages for speech input.** We synthesised the app's own sentences and transcribed them back (`ml/roundtrip.py`, character error rate, lower is better; above 1 means more text was invented than was said): en 0.06, ta 0.12, hi 0.19, mr 0.74, ml 0.99, pa 1.27, kn 1.39, te 1.45, gu 1.46, bn 1.62. In the last group Whisper small writes nonsense, sometimes in another script (Bengali comes back as Telugu). `ML_STT_LANGS` can add languages, but a wrong transcript is worse than the typing box. This test uses a synthetic voice, so it compares languages and proves the pipeline; **it is not accuracy on real speech**, which is untested. Better Indic recognition (IndicWhisper, a larger Whisper on a bigger machine) is the next step.

Numbers: MMS voices cannot read digits, so `₹6,000` becomes words in Hindi, English and Bengali, and digit by digit in the other languages. Whisper often garbles a spoken amount ("छह हज़ार" came back as "चे हजे"), so people must check a transcribed number before relying on it.

## 6. Translation review

### UI strings

The current translations of the UI strings were machine-authored and **need native-speaker review**.

1. Each locale has a reviewer (volunteer or paid) who is a native speaker.
2. Reviewers work from a sheet or PR showing key, English, current translation and screenshot context.
3. Checks: meaning, simple wording (grade 5), the copy rules in their language, consistent terms (e.g. one word for "scheme"), and fit in the layout.
4. Reviewed keys are recorded in `frontend/public/locales/review-status.json` (planned) with reviewer and date.

### Scheme content

Built: see [content/translations/README.md](../content/translations/README.md). `tools/translate-content.mjs` translates English scheme and document text into `content/translations/<lang>.json` for the 8 languages without reviewed text, through `/v1/translate`.

1. These files are separate from the reviewed scheme files; rules and logic are never touched. The app overlays them for display and labels the scheme as machine translated.
2. Each field stores the hash of its English source. Changed English removes that field on the next run, and only what is new or changed is sent to the model.
3. Amounts and numbers must match the English exactly, URLs and acronyms are masked and restored, and banned phrases are rejected in each language. A field that fails stays out, and English shows instead.
4. Planned: native-speaker review (`reviewed_by`, `reviewed_at`) before the "machine translated" note can go, and an API copy of these files. Nothing here has been reviewed by a speaker of these languages.

### Banned phrases in every language

The copy lint keeps a per-language list of phrases equivalent to "you are eligible", "you will get" and "guaranteed" (e.g. Hindi *"आप पात्र हैं"*, *"आपको मिलेगा"*, *"गारंटी"*). Native-speaker reviewers own and extend these lists.
