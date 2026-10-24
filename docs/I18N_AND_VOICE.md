# Languages and voice

> **Status:** the UI i18n and browser speech described in §1–§4 are part of the Phase 0 frontend prototype (`web/`). Server-side speech, content translation and the review tooling (§5–§6) are planned.

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

The list lives in `LANGS` in `web/js/i18n.js`.

## 2. How UI i18n works

**Marking up elements**

```html
<h2 data-i18n="tiles.title">Find help for</h2>
<input data-i18n-attr="placeholder:ask.placeholder,aria-label:ask.label">
<button data-i18n-attr="aria-label:speech.read_page">🔊</button>
```

- `data-i18n="key"` replaces the element's text content.
- `data-i18n-attr="attr:key,attr2:key2"` sets attributes (placeholder, aria-label, title, alt).
- The English text in the HTML is only a fallback for the first paint; the locale file is the source.

**Locale files:** `web/locales/<code>.json`, one per language, with **identical key sets**. Keys are dotted, grouped by area (`hero.*`, `tiles.*`, `ask.*`, `speech.*`, …).

**Loading and fallback**

1. On first visit the language is picked from `navigator.languages` (first supported match), else English.
2. The choice is saved in `localStorage` and reused next time. If storage is unavailable (private mode), the app still works and just re-detects.
3. The locale JSON is fetched and applied to every `data-i18n` / `data-i18n-attr` element.
4. Any key missing from the chosen locale falls back to `en.json`.
5. `<html lang>` is set to the language code and the page title is updated from the locale.

**Formatting:** dates, numbers and currency go through `Intl.DateTimeFormat` / `Intl.NumberFormat` with the `xx-IN` tag (e.g. ₹2,50,000 in Indian grouping). Don't concatenate translated fragments; use whole sentences with `{placeholders}`.

## 3. Adding a key

1. Add the key and English text to `en.json`.
2. Add the same key to **all nine** other locale files. If you can't translate it, copy the English and list the key in the PR so it's queued for translation. (CI will check key parity; see [CONTRIBUTING.md](CONTRIBUTING.md).)
3. Use it via `data-i18n` / `data-i18n-attr`. Never put user-facing text directly in HTML or JS.
4. Run the copy rules on the English text: no "you are eligible", "you will get", "guaranteed".
5. Check the layout with the longest translation (usually Tamil or Malayalam).

## 4. Adding a language

1. Add an entry to `LANGS` in `web/js/i18n.js` (code, native name, speech tag).
2. Create `web/locales/<code>.json` with every key from `en.json`.
3. Add the Noto font for the script (see [DESIGN.md](../DESIGN.md#4-typography)) and check line-height.
4. Test speech: is there a browser voice for the tag on Android Chrome and desktop Chrome? Record the result in the voice coverage table below.
5. Get a native-speaker review (§6) before it's shown by default.

## 5. Voice

### 5.1 Text-to-speech (`web/js/speech.js`)

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

Browser voices vary by device. Several Indic languages have no voice on desktop browsers. Planned fallbacks:

| Gap | Planned fallback |
|---|---|
| No browser TTS voice | Server TTS: Piper or AI4Bharat Indic-TTS, via `/tts`, cached per text hash |
| No browser speech recognition | Server STT: IndicWhisper / Whisper, via `/stt` (audio is processed and deleted, never stored) |
| Offline | Pre-generated audio for fixed UI strings (tiles, questionnaire) cached by the service worker |

The client picks: browser first → server fallback (if online and the user allows sending audio) → text only.

## 6. Translation review

### UI strings

The current translations of the UI strings were machine-authored and **need native-speaker review**.

1. Each locale has a reviewer (volunteer or paid) who is a native speaker.
2. Reviewers work from a sheet or PR showing key, English, current translation and screenshot context.
3. Checks: meaning, simple wording (grade 5), the copy rules in their language, consistent terms (e.g. one word for "scheme"), and fit in the layout.
4. Reviewed keys are recorded in `web/locales/review-status.json` (planned) with reviewer and date.

### Scheme content (planned, Phase 6)

1. IndicTrans2 translates reviewed English/Hindi content into the other languages.
2. Results are cached in `scheme_translation` with `machine_translated = true`.
3. Machine-translated content is shown with a small "machine translated" note until a reviewer approves it (`reviewed_by`, `reviewed_at`).
4. Amounts, dates and scheme names are protected from translation (placeholder masking) and checked after translation.
5. If the source content changes, its translations go back to `machine_translated = true` and are re-queued for review.

### Banned phrases in every language

The copy lint keeps a per-language list of phrases equivalent to "you are eligible", "you will get" and "guaranteed" (e.g. Hindi *"आप पात्र हैं"*, *"आपको मिलेगा"*, *"गारंटी"*). Native-speaker reviewers own and extend these lists.
