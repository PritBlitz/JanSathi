// React binding for i18n.js: every user-facing string goes through t('key').
import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { LANG_KEY, loadLocale, makeFormatters, makeT, speechTag, langName, store } from './i18n.js';

const I18nContext = createContext(null);

export function I18nProvider({ initial, children }) {
  const [state, setState] = useState(initial);

  const setLang = useCallback(async (code) => {
    const next = await loadAll(code);
    setState(next);
  }, []);

  const value = useMemo(() => {
    const { lang, strings, fallback } = state;
    const t = makeT(strings, fallback);
    return { lang, t, setLang, speech: speechTag(lang), langName: langName(lang), ...makeFormatters(lang) };
  }, [state, setLang]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export const useI18n = () => useContext(I18nContext);

/** Loads English (the fallback) and the chosen language, then updates <html lang>, title and description. */
export async function loadAll(code) {
  const fallback = await loadLocale('en');
  let strings = fallback;
  let lang = code;
  try {
    if (code !== 'en') strings = await loadLocale(code);
  } catch {
    lang = 'en';
  }
  store.set(LANG_KEY, lang);
  const t = makeT(strings, fallback);
  document.documentElement.lang = lang;
  document.title = t('meta.title');
  document.querySelector('meta[name="description"]')?.setAttribute('content', t('meta.description'));
  return { lang, strings, fallback };
}
