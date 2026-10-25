// Site chrome: nav (DESIGN §6.2 part 1), preview banner, notices, footer (§7 full-bleed footer).
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { BrandMark, Icon } from './Icon.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp, KEYS } from '../lib/app-state.jsx';
import { LANGS, store } from '../lib/i18n.js';
import { useSay, useSpeaking } from '../lib/use-speech.js';
import { stop } from '../lib/speech.js';
import { readableText } from '../lib/text.js';

const NAV = [
  { href: '#/schemes', key: 'nav.schemes', route: 'schemes' },
  { href: '#/how', key: 'nav.how', route: 'how' },
  { href: '#/family', key: 'nav.family', route: 'family' },
  { href: '#/checklist', key: 'nav.checklist', route: 'checklist' },
  { href: '#/changes', key: 'nav.changes', route: 'changes', ifChanges: true },
  { href: '#/help', key: 'nav.help', route: 'help' },
];

/** Nav entries to show: 'What changed' only when content records a change. */
function useNav() {
  const { bundle } = useApp();
  const hasChanges = bundle.schemes.some((s) => s.changes?.length);
  return NAV.filter((n) => !n.ifChanges || hasChanges);
}

const THEMES = ['system', 'light', 'dark'];
const THEME_ICON = { system: 'auto', light: 'sun', dark: 'moon' };

export function applyTheme(theme) {
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
  else delete document.documentElement.dataset.theme;
}

function ThemeButton() {
  const { t } = useI18n();
  const [theme, setTheme] = useState(() => store.get(KEYS.theme) || 'system');
  const next = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length];
  return (
    <button
      className="icon-btn"
      type="button"
      aria-label={`${t('theme.label')}: ${t(`theme.${theme}`)}`}
      title={`${t('theme.label')}: ${t(`theme.${theme}`)}`}
      onClick={() => {
        setTheme(next);
        store.set(KEYS.theme, next);
        applyTheme(next);
      }}
    >
      <Icon name={THEME_ICON[theme]} />
    </button>
  );
}

export function LanguageSelect({ id = 'lang-select' }) {
  const { t, lang, setLang } = useI18n();
  return (
    <label className="lang" htmlFor={id}>
      <Icon name="globe" />
      <span className="visually-hidden">{t('nav.language')}</span>
      <select
        id={id}
        value={lang}
        onChange={(e) => {
          stop();
          setLang(e.target.value);
        }}
      >
        {LANGS.map((l) => (
          <option key={l.code} value={l.code} lang={l.code}>
            {l.name}
          </option>
        ))}
      </select>
    </label>
  );
}

/** Below this width the nav is always compact (menu button). */
const FULL_NAV_MIN = 1140;

/**
 * Whether the nav must use the menu button. Wider screens show the links in a row, unless the
 * labels in this language don't fit (Tamil and Malayalam labels can be twice as long as English):
 * the full row is rendered once, measured, and kept only if nothing overflows.
 */
function useCompact(inner, lang, items) {
  const [compact, setCompact] = useState(() => window.innerWidth < FULL_NAV_MIN);
  const needed = useRef(null); // width the full row needs in this language; null = not measured
  useLayoutEffect(() => {
    needed.current = null;
    setCompact(window.innerWidth < FULL_NAV_MIN);
  }, [lang, items]);
  useLayoutEffect(() => {
    const el = inner.current;
    if (compact || !el || needed.current !== null) return;
    needed.current = el.scrollWidth;
    if (el.scrollWidth > el.clientWidth + 1) setCompact(true);
  });
  useEffect(() => {
    const onResize = () => {
      const el = inner.current;
      if (!el) return;
      if (window.innerWidth < FULL_NAV_MIN) setCompact(true);
      else if (needed.current === null) setCompact(false);
      else setCompact(needed.current > el.clientWidth + 1);
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [inner]);
  return compact;
}

export function Nav({ route }) {
  const { t, lang } = useI18n();
  const { setVoiceMode } = useApp();
  const nav = useNav();
  const inner = useRef(null);
  const compact = useCompact(inner, lang, nav.length);
  const say = useSay();
  const speaking = useSpeaking();
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 8);
    on();
    window.addEventListener('scroll', on, { passive: true });
    return () => window.removeEventListener('scroll', on);
  }, []);
  useEffect(() => setOpen(false), [route.name, route.arg]);
  useEffect(() => {
    // A class, not html:has(): :has() on the root makes every style change re-check it.
    document.documentElement.classList.toggle('menu-lock', open);
    if (!open) return undefined;
    const onKey = (e) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);

  return (
    <header className={`nav ${compact ? 'compact' : ''} ${scrolled ? 'scrolled' : ''} ${open && compact ? 'menu-open' : ''}`}>
      <div className="nav-inner" ref={inner}>
        <a className="brand" href="#/" aria-label={t('nav.home')}>
          <BrandMark />
          <span className="brand-word">JanSathi</span>
        </a>
        <nav className={`nav-links ${open ? 'open' : ''}`} id="nav-links" aria-label={t('nav.main')}>
          {nav.map((n, i) => (
            <a key={n.href} href={n.href} aria-current={route.name === n.route ? 'page' : undefined} style={{ '--i': i }}>
              {t(n.key)}
            </a>
          ))}
          <div className="nav-menu-extra">
            <LanguageSelect id="lang-select-menu" />
          </div>
        </nav>
        <div className="nav-tools">
          <div className="nav-lang">
            <LanguageSelect />
          </div>
          <ThemeButton />
          <button
            className="btn btn-outline btn-listen"
            type="button"
            aria-pressed={speaking}
            onClick={() => {
              if (speaking) return stop();
              setVoiceMode(true);
              say(readableText(document.getElementById('main')));
            }}
          >
            <Icon name={speaking ? 'stop' : 'speaker'} size={18} />
            <span className="btn-listen-label">{t(speaking ? 'nav.stop' : 'nav.listen')}</span>
          </button>
          <button
            className="icon-btn menu-btn"
            type="button"
            aria-expanded={open}
            aria-controls="nav-links"
            aria-label={t(open ? 'nav.close' : 'nav.menu')}
            onClick={() => setOpen(!open)}
          >
            <Icon name={open ? 'close' : 'menu'} />
          </button>
        </div>
      </div>
    </header>
  );
}

export function PreviewBanner() {
  const { t } = useI18n();
  return (
    <div className="banner" role="note">
      <Icon name="warn" size={18} />
      <span>{t('preview.banner')}</span>
    </div>
  );
}

export function Notice() {
  const { notice } = useApp();
  return (
    <div className="notice" role="status" aria-live="polite">
      {notice && <p>{notice}</p>}
    </div>
  );
}

export function Footer() {
  const { t, setLang } = useI18n();
  const nav = useNav();
  const [reduced, setReduced] = useState(() => store.get(KEYS.motion) === '1');
  useEffect(() => {
    document.documentElement.classList.toggle('reduce-motion', reduced);
    store.set(KEYS.motion, reduced ? '1' : '0');
  }, [reduced]);

  return (
    <footer className="footer">
      <div className="footer-grid">
        <div className="footer-lead">
          <p className="footer-statement">{t('footer.disclaimer')}</p>
        </div>
        <div>
          <h2 className="footer-h">{t('footer.explore')}</h2>
          <ul className="footer-list">
            {nav.map((n) => (
              <li key={n.href}>
                <a href={n.href}>{t(n.key)}</a>
              </li>
            ))}
            <li>
              <a href="#/privacy">{t('footer.privacy')}</a>
            </li>
            <li>
              <a href="https://github.com/PritBlitz/JanSathi" rel="noopener">
                {t('footer.source')}
              </a>
            </li>
          </ul>
        </div>
        <div>
          <h2 className="footer-h">{t('footer.languages')}</h2>
          <ul className="footer-list footer-langs" aria-label={t('nav.language')}>
            {LANGS.map((l) => (
              <li key={l.code}>
                <button
                  type="button"
                  lang={l.code}
                  onClick={() => {
                    stop();
                    setLang(l.code);
                    window.scrollTo(0, 0);
                  }}
                >
                  {l.name}
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>
      <div className="footer-bottom">
        <label className="toggle">
          <input type="checkbox" checked={reduced} onChange={(e) => setReduced(e.target.checked)} /> <span>{t('footer.reduce_motion')}</span>
        </label>
      </div>
      <p className="footer-word" aria-hidden="true">
        JanSathi
      </p>
    </footer>
  );
}
