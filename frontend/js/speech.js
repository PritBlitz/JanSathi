// Browser speech: text-to-speech (speechSynthesis) and speech-to-text (SpeechRecognition).
// Never reads text in another language's voice: if there is no voice for the language,
// speak() returns false and the caller shows a notice. See docs/I18N_AND_VOICE.md §5.

const synth = typeof window !== 'undefined' ? window.speechSynthesis : undefined;
const Recognition = typeof window !== 'undefined' ? window.SpeechRecognition || window.webkitSpeechRecognition : undefined;
let voices = [];
let speakingListeners = new Set();

function refreshVoices() {
  voices = synth ? synth.getVoices() : [];
}
if (synth) {
  refreshVoices();
  synth.addEventListener?.('voiceschanged', refreshVoices);
}

export const canSpeak = () => !!synth;
export const canListen = () => !!Recognition;

/** Finds a voice for a BCP-47 tag such as hi-IN: exact match first, then the base language. */
export function voiceFor(tag) {
  if (!voices.length) refreshVoices();
  const lower = tag.toLowerCase();
  const base = lower.split('-')[0];
  return (
    voices.find((v) => v.lang.toLowerCase().replace('_', '-') === lower) ||
    voices.find((v) => v.lang.toLowerCase().split(/[-_]/)[0] === base) ||
    null
  );
}

export const hasVoice = (tag) => !!voiceFor(tag);

export function onSpeakingChange(fn) {
  speakingListeners.add(fn);
  return () => speakingListeners.delete(fn);
}
const notify = (on) => speakingListeners.forEach((fn) => fn(on));

/**
 * Reads text aloud in the voice for tag. Long text is split into sentences so
 * Android Chrome does not cut it off. Returns false if there is no voice.
 */
export function speak(text, tag) {
  if (!synth) return false;
  const voice = voiceFor(tag);
  if (!voice) return false;
  synth.cancel();
  const chunks = String(text)
    .replace(/\s+/g, ' ')
    .split(/(?<=[.!?।॥:])\s+/)
    .filter(Boolean);
  chunks.forEach((chunk, i) => {
    const u = new SpeechSynthesisUtterance(chunk);
    u.voice = voice;
    u.lang = voice.lang;
    u.rate = 0.9;
    if (i === 0) u.onstart = () => notify(true);
    if (i === chunks.length - 1) {
      u.onend = () => notify(false);
      u.onerror = () => notify(false);
    }
    synth.speak(u);
  });
  return true;
}

export function stop() {
  if (synth) synth.cancel();
  notify(false);
}

/**
 * Listens once. Calls onResult(text, isFinal) as words arrive; resolves with the final text.
 * Rejects with Error('unsupported' | 'denied' | 'no-speech' | other).
 */
export function listen(tag, onResult = () => {}) {
  return new Promise((resolve, reject) => {
    if (!Recognition) return reject(new Error('unsupported'));
    const rec = new Recognition();
    rec.lang = tag;
    rec.interimResults = true;
    rec.maxAlternatives = 3;
    let finalText = '';
    rec.onresult = (e) => {
      let interim = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r.isFinal) finalText += r[0].transcript;
        else interim += r[0].transcript;
      }
      onResult((finalText + interim).trim(), !interim);
    };
    rec.onerror = (e) => reject(new Error(e.error === 'not-allowed' || e.error === 'service-not-allowed' ? 'denied' : e.error || 'error'));
    rec.onend = () => resolve(finalText.trim());
    try {
      rec.start();
    } catch (err) {
      reject(err);
    }
  });
}
