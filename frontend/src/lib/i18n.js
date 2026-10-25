// UI internationalisation helpers: language list, locale loading with English fallback,
// persistence and Intl formatting. React components use them through I18nProvider (i18n.jsx).
// See docs/I18N_AND_VOICE.md.

export const LANGS = [
  { code: 'en', name: 'English', speech: 'en-IN' },
  { code: 'hi', name: 'हिन्दी', speech: 'hi-IN' },
  { code: 'bn', name: 'বাংলা', speech: 'bn-IN' },
  { code: 'ta', name: 'தமிழ்', speech: 'ta-IN' },
  { code: 'te', name: 'తెలుగు', speech: 'te-IN' },
  { code: 'mr', name: 'मराठी', speech: 'mr-IN' },
  { code: 'gu', name: 'ગુજરાતી', speech: 'gu-IN' },
  { code: 'kn', name: 'ಕನ್ನಡ', speech: 'kn-IN' },
  { code: 'ml', name: 'മലയാളം', speech: 'ml-IN' },
  { code: 'pa', name: 'ਪੰਜਾਬੀ', speech: 'pa-IN' },
];

export const LANG_KEY = 'jansathi.lang';
const cache = {};

export const store = {
  get(key) {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, value);
    } catch {
      /* private mode or blocked storage: the app still works without saving */
    }
  },
  remove(key) {
    try {
      localStorage.removeItem(key);
    } catch {
      /* ignore */
    }
  },
};

export async function loadLocale(code) {
  if (!cache[code]) {
    const res = await fetch(`${import.meta.env.BASE_URL}locales/${code}.json`);
    if (!res.ok) throw new Error(`locale ${code}: HTTP ${res.status}`);
    cache[code] = await res.json();
  }
  return cache[code];
}

export function detectLang() {
  const saved = store.get(LANG_KEY);
  if (saved && LANGS.some((l) => l.code === saved)) return saved;
  for (const tag of navigator.languages || [navigator.language || 'en']) {
    const base = String(tag).toLowerCase().split('-')[0];
    if (LANGS.some((l) => l.code === base)) return base;
  }
  return 'en';
}

export const speechTag = (code) => LANGS.find((l) => l.code === code)?.speech || 'en-IN';
export const langName = (code) => LANGS.find((l) => l.code === code)?.name || code;
const intlTag = (code) => `${code}-IN`;

/** Translator for one language: missing keys fall back to English, then to the key. Fills {placeholders}. */
export function makeT(strings, fallback) {
  return (key, vars) => {
    let s = strings[key] ?? fallback[key] ?? key;
    if (vars) s = s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
    return s;
  };
}

export function makeFormatters(code) {
  const numbers = new Intl.NumberFormat(intlTag(code));
  return {
    formatNumber: (n) => numbers.format(n),
    formatDate(iso) {
      if (!iso) return '';
      const parts = String(iso).slice(0, 10).split('-').map(Number);
      const date = new Date(Date.UTC(parts[0], (parts[1] || 1) - 1, parts[2] || 1));
      const opts =
        parts.length >= 3 ? { day: 'numeric', month: 'long', year: 'numeric' } : parts.length === 2 ? { month: 'long', year: 'numeric' } : { year: 'numeric' };
      return new Intl.DateTimeFormat(intlTag(code), { ...opts, timeZone: 'UTC' }).format(date);
    },
    /** Picks the text for this language from a {en, hi, …} object. Returns {text, lang}. */
    pick(obj) {
      if (!obj) return { text: '', lang: 'en' };
      if (obj[code]) return { text: obj[code], lang: code };
      return { text: obj.en ?? '', lang: 'en' };
    },
  };
}
