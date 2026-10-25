# Languages and voice

> **Status:** the UI i18n and browser speech in §1–§5.2 are built (`frontend/src/lib/i18n.js` and its React provider `i18n-context.jsx`, `frontend/src/lib/speech.js` and its hooks `use-speech.js`), with all 10 locale files and `frontend/public/locales/review-status.json`; `npm run check:locales` checks key parity. The 9 non-English locales are machine-authored and unreviewed, and 32 newer keys still hold the English text (`untranslated_keys` in `review-status.json`). Server-side speech, content translation and the review tooling (§5.3, §6) are planned.

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
4. Reviewed keys are recorded in `frontend/public/locales/review-status.json` (planned) with reviewer and date.

### Scheme content (planned, Phase 6)

1. IndicTrans2 translates reviewed English/Hindi content into the other languages.
2. Results are cached in `scheme_translation` with `machine_translated = true`.
3. Machine-translated content is shown with a small "machine translated" note until a reviewer approves it (`reviewed_by`, `reviewed_at`).
4. Amounts, dates and scheme names are protected from translation (placeholder masking) and checked after translation.
5. If the source content changes, its translations go back to `machine_translated = true` and are re-queued for review.

### Banned phrases in every language

The copy lint keeps a per-language list of phrases equivalent to "you are eligible", "you will get" and "guaranteed" (e.g. Hindi *"आप पात्र हैं"*, *"आपको मिलेगा"*, *"गारंटी"*). Native-speaker reviewers own and extend these lists.
