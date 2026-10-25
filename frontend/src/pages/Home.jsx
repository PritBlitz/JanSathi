// Home: the hero (DESIGN §6, kept) and the Awwwards-pattern sections below it (§7):
// marquee strip, scroll-lit manifesto, big-number stats from our own data, sticky storytelling,
// an index list of categories, the trust band and a closing call to action.
import { useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { Reveal } from '../components/Reveal.jsx';
import { TrustChip } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { LANGS } from '../lib/i18n.js';
import { CATEGORY_ICONS, langProps, schemeText } from '../lib/scheme.js';

function Hero({ onAsk }) {
  const { t, lang } = useI18n();
  const { bundle, preview } = useApp();
  const [hasImage, setHasImage] = useState(false);
  const rail = useRef(null);

  useEffect(() => {
    const img = new Image();
    img.onload = () => setHasImage(true);
    img.src = `${import.meta.env.BASE_URL}img/collage.svg`;
  }, []);

  // Published schemes have verified sources. In preview mode, drafts are shown with their warning.
  const list = bundle.schemes.filter((s) => preview || s.status === 'published');
  const scroll = (dir) =>
    rail.current?.scrollBy({
      left: dir * rail.current.clientWidth * 0.8,
      behavior: document.documentElement.classList.contains('reduce-motion') ? 'auto' : 'smooth',
    });

  return (
    <section className="hero">
      <div className="hero-word-wrap">
        <h1 className={`hero-word ${hasImage ? 'has-image' : ''}`} aria-label={t('hero.display_label')}>
          <span aria-hidden="true">{t('hero.display')}</span>
        </h1>
      </div>
      <p className="hero-lead">{t('hero.lead')}</p>
      <div className="hero-ctas">
        <a className="btn btn-primary btn-hero" href="#/check">
          {t('hero.cta_primary')} <Icon name="arrow" size={20} />
        </a>
        <button className="btn btn-outline btn-hero" type="button" onClick={onAsk}>
          <Icon name="mic" size={20} /> {t('hero.cta_voice')}
        </button>
      </div>
      {list.length > 0 && (
        <section className="rail-wrap" aria-labelledby="rail-title">
          <div className="rail-head">
            <h2 className="kicker" id="rail-title">
              {t(preview ? 'rail.title' : 'rail.published')}
            </h2>
            <div className="rail-btns">
              <button className="icon-btn" type="button" aria-label={t('rail.prev')} onClick={() => scroll(-1)}>
                <Icon name="arrowLeft" />
              </button>
              <button className="icon-btn" type="button" aria-label={t('rail.next')} onClick={() => scroll(1)}>
                <Icon name="arrow" />
              </button>
            </div>
          </div>
          <ul className="rail" ref={rail}>
            {list.map((s) => (
              <li key={s.slug}>
                <a className="rail-card" href={`#/scheme/${s.slug}`}>
                  <span className="avatar" aria-hidden="true">
                    {s.icon}
                  </span>
                  <span className="rail-text">
                    <span className="card-kicker">{t(`cat.${s.category}`)}</span>
                    <span className="rail-title" {...langProps(s, lang)}>
                      {schemeText(s, 'name', lang)}
                    </span>
                    {s.status !== 'published' && <span className="rail-draft">{t('preview.badge')}</span>}
                  </span>
                  <span className="rail-go" aria-hidden="true">
                    <Icon name="arrowUpRight" size={20} />
                  </span>
                </a>
              </li>
            ))}
          </ul>
        </section>
      )}
    </section>
  );
}

/** Language names in their own scripts, scrolling. Decorative: the same list is in the footer. */
function Marquee() {
  const { t } = useI18n();
  const [paused, setPaused] = useState(false);
  const items = [...LANGS, ...LANGS];
  return (
    <section className={`marquee ${paused ? 'paused' : ''}`} aria-label={t('marquee.label')}>
      <div className="marquee-track" aria-hidden="true">
        {items.map((l, i) => (
          <span className="marquee-item" key={i} lang={l.code}>
            {l.name}
            <span className="marquee-star">✺</span>
          </span>
        ))}
      </div>
      <button className="marquee-btn" type="button" onClick={() => setPaused(!paused)} aria-label={t(paused ? 'marquee.play' : 'marquee.pause')}>
        <Icon name={paused ? 'play' : 'pause'} size={18} />
      </button>
    </section>
  );
}

function Manifesto() {
  const { t } = useI18n();
  return (
    <section className="section manifesto" aria-labelledby="manifesto-kicker">
      <p className="kicker" id="manifesto-kicker">
        {t('manifesto.kicker')}
      </p>
      <p className="manifesto-text">
        <span className="lit">{t('manifesto.line1')}</span> <span className="lit">{t('manifesto.line2')}</span>{' '}
        <span className="lit">{t('manifesto.line3')}</span>
      </p>
    </section>
  );
}

/** Real figures counted from the bundle in this app; nothing rounded up or invented (DESIGN §7). */
function Stats() {
  const { t, formatNumber } = useI18n();
  const { bundle } = useApp();
  const stats = useMemo(() => {
    const urls = new Set();
    let rules = 0;
    for (const s of bundle.schemes) {
      s.sources.forEach((src) => urls.add(src.url));
      rules += s.rules.length;
    }
    return {
      schemes: bundle.schemes.length,
      sources: urls.size,
      rules,
      languages: LANGS.length,
      verified: bundle.schemes.filter((s) => s.status === 'published').length,
    };
  }, [bundle]);
  const items = ['schemes', 'sources', 'rules', 'languages'];
  return (
    <section className="section stats" aria-labelledby="stats-title">
      <div className="section-head">
        <p className="kicker">{t('stats.kicker')}</p>
        <h2 className="section-title" id="stats-title">
          {t('stats.title')}
        </h2>
      </div>
      <dl className="stats-grid">
        {items.map((k, i) => (
          <Reveal className="stat" key={k} delay={i * 80}>
            <dt>{t(`stats.${k}`)}</dt>
            <dd className="stat-num">{formatNumber(stats[k])}</dd>
          </Reveal>
        ))}
      </dl>
      <p className="small muted stats-note">{t('stats.verified_note', { n: formatNumber(stats.verified) })}</p>
    </section>
  );
}

/** Sticky storytelling: the numeral stays pinned while the steps scroll past (position: sticky only). */
function HowItWorks() {
  const { t, formatNumber } = useI18n();
  const [active, setActive] = useState(0);
  const steps = useRef([]);
  useEffect(() => {
    if (!('IntersectionObserver' in window)) return undefined;
    const io = new IntersectionObserver(
      (entries) => entries.forEach((e) => e.isIntersecting && setActive(Number(e.target.dataset.step))),
      { rootMargin: '-45% 0px -45% 0px' },
    );
    steps.current.forEach((el) => el && io.observe(el));
    return () => io.disconnect();
  }, []);
  return (
    <section className="section how" id="how" aria-labelledby="how-title">
      <div className="how-pin">
        <p className="kicker">{t('how.kicker')}</p>
        <h2 className="section-title" id="how-title">
          {t('how.title')}
        </h2>
        <p className="how-num" aria-hidden="true">
          {formatNumber(active + 1).padStart(2, formatNumber(0))}
        </p>
      </div>
      <ol className="how-steps">
        {[1, 2, 3, 4].map((n, i) => (
          <li key={n} ref={(el) => (steps.current[i] = el)} data-step={i} className={active === i ? 'active' : ''}>
            <span className="how-step-num" aria-hidden="true">
              {formatNumber(n)}
            </span>
            <h3>{t(`how.step${n}.title`)}</h3>
            <p>{t(`how.step${n}.body`)}</p>
          </li>
        ))}
      </ol>
    </section>
  );
}

function Categories() {
  const { t, formatNumber } = useI18n();
  const { bundle } = useApp();
  const counts = useMemo(() => {
    const m = new Map();
    bundle.schemes.forEach((s) => m.set(s.category, (m.get(s.category) || 0) + 1));
    return [...m.entries()];
  }, [bundle]);
  return (
    <section className="section" aria-labelledby="tiles-title">
      <div className="section-head">
        <p className="kicker">{t('nav.schemes')}</p>
        <h2 className="section-title" id="tiles-title">
          {t('tiles.title')}
        </h2>
      </div>
      <ul className="index-list">
        {counts.map(([c, n]) => (
          <Reveal as="li" key={c}>
            <a className="index-row" href={`#/schemes/${c}`}>
              <span className="index-icon" aria-hidden="true">
                {CATEGORY_ICONS[c] || '📌'}
              </span>
              <span className="index-label">{t(`cat.${c}`)}</span>
              <span className="index-count">{t('tiles.count', { n: formatNumber(n) })}</span>
              <span className="index-go" aria-hidden="true">
                <Icon name="arrowUpRight" size={24} />
              </span>
            </a>
          </Reveal>
        ))}
      </ul>
    </section>
  );
}

function TrustBand() {
  const { t } = useI18n();
  return (
    <section className="band band-ink" aria-labelledby="trust-title">
      <div className="band-inner">
        <p className="kicker">{t('trust.kicker')}</p>
        <h2 className="section-title" id="trust-title">
          {t('trust.title')}
        </h2>
        <ul className="trust-grid">
          {['confirmed', 'guidance', 'uncertain'].map((label, i) => (
            <Reveal as="li" className="trust-card" key={label} delay={i * 80}>
              <TrustChip label={label} />
              <p>{t(`trust.${label === 'uncertain' ? 'unverified' : label}_body`)}</p>
            </Reveal>
          ))}
        </ul>
      </div>
    </section>
  );
}

function CtaBand({ onAsk }) {
  const { t } = useI18n();
  return (
    <section className="band band-cta" aria-labelledby="cta-title">
      <div className="band-inner">
        <h2 className="cta-title" id="cta-title">
          {t('cta.title')}
        </h2>
        <p className="cta-body">{t('cta.body')}</p>
        <div className="hero-ctas cta-actions">
          <a className="btn btn-ink btn-hero" href="#/check">
            {t('hero.cta_primary')} <Icon name="arrow" size={20} />
          </a>
          <button className="btn btn-outline-ink btn-hero" type="button" onClick={onAsk}>
            <Icon name="mic" size={20} /> {t('hero.cta_voice')}
          </button>
        </div>
      </div>
    </section>
  );
}

export function Home({ route, onAsk }) {
  useEffect(() => {
    if (route.name === 'how') requestAnimationFrame(() => document.getElementById('how')?.scrollIntoView());
  }, [route.name]);
  return (
    <>
      <Hero onAsk={onAsk} />
      <Marquee />
      <Manifesto />
      <Stats />
      <HowItWorks />
      <Categories />
      <TrustBand />
      <CtaBand onAsk={onAsk} />
    </>
  );
}
