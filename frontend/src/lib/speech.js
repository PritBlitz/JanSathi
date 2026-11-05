// Browser speech: text-to-speech (speechSynthesis) and speech-to-text (SpeechRecognition).
// Never reads text in another language's voice: if there is no voice for the language,
// speak() returns false and the caller shows a notice. See docs/I18N_AND_VOICE.md §5.
// When the API has speech models (ml/), they stand in for what this browser lacks: a language with
// no voice is read by the server, and a browser without recognition records a short clip for it.
// Clips are sent only after the person taps the microphone, and the server does not keep them.
import { apiUrl, hasApi } from './data.js';

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

let server = { tts: [], stt: [] };
let playing = null;
const RECORD_MS = 7000;
const baseOf = (tag) => tag.split('-')[0];

/** Asks the API which languages it can speak and hear. Quietly does nothing without one. */
export async function loadServerSpeech() {
  if (!hasApi()) return;
  try {
    const res = await fetch(apiUrl('/speech/status'));
    if (res.ok) server = await res.json();
  } catch {
    /* no speech service: the browser's own speech is all there is */
  }
}

const canRecord = () => typeof MediaRecorder !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;
export const canSpeak = (tag) => !!synth || (!!tag && server.tts.includes(baseOf(tag)));
export const canListen = (tag) => !!Recognition || (canRecord() && (tag ? server.stt.includes(baseOf(tag)) : server.stt.length > 0));

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

export const hasVoice = (tag) => !!voiceFor(tag) || server.tts.includes(baseOf(tag));

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
  const voice = synth ? voiceFor(tag) : null;
  const chunks = String(text)
    .replace(/\s+/g, ' ')
    .split(/(?<=[.!?।॥:])\s+/)
    .filter(Boolean);
  if (!voice) {
    if (!server.tts.includes(baseOf(tag))) return false;
    stop();
    serverSpeak(chunks, baseOf(tag));
    return true;
  }
  synth.cancel();
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

/** Plays sentences one after another, fetching the next while the current one plays. */
async function serverSpeak(chunks, lang) {
  const run = {};
  playing = run;
  const fetchClip = async (text) => {
    const res = await fetch(apiUrl('/speech/tts'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text, lang }) });
    if (!res.ok) throw new Error(`tts ${res.status}`);
    return URL.createObjectURL(await res.blob());
  };
  notify(true);
  try {
    let next = fetchClip(chunks[0]);
    for (let i = 0; i < chunks.length && playing === run; i++) {
      const url = await next;
      if (i + 1 < chunks.length) next = fetchClip(chunks[i + 1]);
      run.audio = new Audio(url);
      await new Promise((done, fail) => {
        run.audio.onended = done;
        run.audio.onerror = fail;
        run.stop = done;
        run.audio.play().catch(fail);
      });
      URL.revokeObjectURL(url);
    }
  } catch {
    /* network or playback failed: stop quietly, the text is on the screen */
  }
  if (playing === run) {
    playing = null;
    notify(false);
  }
}

export function stop() {
  if (synth) synth.cancel();
  if (playing) {
    playing.audio?.pause();
    playing.stop?.();
    playing = null;
  }
  notify(false);
}

/** Records a short clip and has the server transcribe it. */
async function listenOnServer(tag, onResult) {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch {
    throw new Error('denied');
  }
  const rec = new MediaRecorder(stream);
  const parts = [];
  rec.ondataavailable = (e) => parts.push(e.data);
  const stopped = new Promise((done) => (rec.onstop = done));
  rec.start();
  setTimeout(() => rec.state === 'recording' && rec.stop(), RECORD_MS);
  await stopped;
  stream.getTracks().forEach((t) => t.stop());
  const res = await fetch(apiUrl(`/speech/stt?lang=${baseOf(tag)}`), { method: 'POST', headers: { 'Content-Type': rec.mimeType || 'audio/webm' }, body: new Blob(parts, { type: rec.mimeType }) });
  if (!res.ok) throw new Error('error');
  const { text } = await res.json();
  if (!text?.trim()) throw new Error('no-speech');
  onResult(text.trim(), true);
  return text.trim();
}

/**
 * Listens once. Calls onResult(text, isFinal) as words arrive; resolves with the final text.
 * Rejects with Error('unsupported' | 'denied' | 'no-speech' | other).
 */
export function listen(tag, onResult = () => {}) {
  if (!Recognition && canListen(tag)) return listenOnServer(tag, onResult);
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
