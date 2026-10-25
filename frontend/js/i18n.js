// UI internationalisation: language list, locale loading with English fallback,
// data-i18n / data-i18n-attr application, persistence, and Intl formatting.
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

const STORAGE_KEY = 'jansathi.lang';
const cache = {};
let current = 'en';
let strings = {};
let fallback = {};

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

async function load(code) {
  if (!cache[code]) {
    const res = await fetch(`locales/${code}.json`);
    if (!res.ok) throw new Error(`locale ${code}: HTTP ${res.status}`);
    cache[code] = await res.json();
  }
  return cache[code];
}

export function detectLang() {
  const saved = store.get(STORAGE_KEY);
  if (saved && LANGS.some((l) => l.code === saved)) return saved;
  for (const tag of navigator.languages || [navigator.language || 'en']) {
    const base = String(tag).toLowerCase().split('-')[0];
    if (LANGS.some((l) => l.code === base)) return base;
  }
  return 'en';
}

export const lang = () => current;
export const speechTag = (code = current) => LANGS.find((l) => l.code === code)?.speech || 'en-IN';
export const langName = (code = current) => LANGS.find((l) => l.code === code)?.name || code;
export const intlTag = (code = current) => `${code}-IN`;

/** Translates a key, filling {placeholders}. Missing keys fall back to English, then to the key. */
export function t(key, vars) {
  let s = strings[key] ?? fallback[key] ?? key;
  if (vars) s = s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
  return s;
}

export const has = (key) => key in strings || key in fallback;

/** Applies data-i18n and data-i18n-attr="attr:key,attr2:key2" under root. */
export function apply(root = document) {
  root.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = t(el.dataset.i18n);
  });
  root.querySelectorAll('[data-i18n-attr]').forEach((el) => {
    for (const pair of el.dataset.i18nAttr.split(',')) {
      const [attr, key] = pair.split(':').map((x) => x.trim());
      if (attr && key) el.setAttribute(attr, t(key));
    }
  });
}

export async function setLang(code) {
  if (!LANGS.some((l) => l.code === code)) code = 'en';
  fallback = await load('en');
  try {
    strings = code === 'en' ? fallback : await load(code);
  } catch {
    strings = fallback;
    code = 'en';
  }
  current = code;
  store.set(STORAGE_KEY, code);
  document.documentElement.lang = code;
  document.title = t('meta.title');
  document.querySelector('meta[name="description"]')?.setAttribute('content', t('meta.description'));
  apply();
  document.dispatchEvent(new CustomEvent('langchange', { detail: { lang: code } }));
}

export const formatNumber = (n) => new Intl.NumberFormat(intlTag()).format(n);
export const formatInr = (n) =>
  new Intl.NumberFormat(intlTag(), { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

export function formatDate(iso) {
  if (!iso) return '';
  const parts = String(iso).split('-').map(Number);
  const date = new Date(Date.UTC(parts[0], (parts[1] || 1) - 1, parts[2] || 1));
  const opts = parts.length >= 3 ? { day: 'numeric', month: 'long', year: 'numeric' } : parts.length === 2 ? { month: 'long', year: 'numeric' } : { year: 'numeric' };
  return new Intl.DateTimeFormat(intlTag(), { ...opts, timeZone: 'UTC' }).format(date);
}

/** Picks the text for the current language from a {en, hi, …} object. Returns {text, lang}. */
export function pick(obj) {
  if (!obj) return { text: '', lang: 'en' };
  if (obj[current]) return { text: obj[current], lang: current };
  return { text: obj.en ?? '', lang: 'en' };
}
