// Smaller pages: scheme list, checklist, search, nearby help, privacy.
import { useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { PageHead, SchemeCard, EmptyState } from '../components/SchemeParts.jsx';
import { Warn } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { CATEGORY_ICONS, schemeText } from '../lib/scheme.js';
import { searchSchemes } from '../lib/text.js';
import { go } from '../lib/router.js';

export function Schemes({ category }) {
  const { t } = useI18n();
  const { bundle, result } = useApp();
  const cats = [...new Set(bundle.schemes.map((s) => s.category))];
  const list = bundle.schemes.filter((s) => !category || s.category === category);
  const chip = (href, active, icon, label) => (
    <li key={href}>
      <a className={`filter-chip ${active ? 'active' : ''}`} href={href} aria-current={active ? 'page' : undefined}>
        <span aria-hidden="true">{icon}</span> {label}
      </a>
    </li>
  );
  return (
    <div className="page">
      <PageHead kicker={t('nav.schemes')} title={category ? t(`cat.${category}`) : t('schemes.title')} lead={t('schemes.lead')} />
      <ul className="filter-row">
        {chip('#/schemes', !category, '📋', t('schemes.all'))}
        {cats.map((c) => chip(`#/schemes/${c}`, c === category, CATEGORY_ICONS[c] || '📌', t(`cat.${c}`)))}
      </ul>
      <ul className="card-grid">
        {list.map((s) => (
          <SchemeCard key={s.slug} scheme={s} result={result.results.find((r) => r.scheme === s.slug)} />
        ))}
      </ul>
    </div>
  );
}

export function Checklist() {
  const { t, lang, pick, formatNumber } = useI18n();
  const { bundle, checklist, schemeBy, setHave, clearChecklist } = useApp();
  const slugs = checklist.schemes.filter((slug) => schemeBy(slug));

  if (!slugs.length) {
    return (
      <div className="page">
        <PageHead kicker={t('nav.checklist')} title={t('checklist.title')} />
        <EmptyState
          title={t('checklist.empty')}
          action={
            <a className="btn btn-primary" href="#/schemes">
              {t('schemes.title')} <Icon name="arrow" size={18} />
            </a>
          }
        />
      </div>
    );
  }

  const docs = new Map();
  for (const slug of slugs) {
    const s = schemeBy(slug);
    for (const d of s.documents) {
      const entry = docs.get(d.code) || { code: d.code, mandatory: false, schemes: [] };
      entry.mandatory ||= d.mandatory;
      entry.schemes.push(schemeText(s, 'name', lang));
      docs.set(d.code, entry);
    }
  }
  const items = [...docs.values()].sort((a, b) => Number(b.mandatory) - Number(a.mandatory));
  const have = items.filter((d) => checklist.have[d.code] === true).length;
  // A scheme is ready when the user has every document it requires.
  const ready = slugs.map(schemeBy).filter((s) => s.documents.every((d) => !d.mandatory || checklist.have[d.code] === true));
  const pct = Math.round((have / items.length) * 100);

  return (
    <div className="page">
      <PageHead kicker={t('nav.checklist')} title={t('checklist.title')} lead={t('checklist.lead')} />
      <div className="check-progress">
        <p className="check-count">
          <span className="summary-num">{formatNumber(have)}</span>
          <span>{t('checklist.progress', { have: formatNumber(have), total: formatNumber(items.length) })}</span>
        </p>
        <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label={t('checklist.title')}>
          <span style={{ width: `${pct}%` }} />
        </div>
        <div className="actions">
          <a className="btn btn-primary" href="#/slip">
            {t('slip.button')} <Icon name="arrow" size={18} />
          </a>
        </div>
      </div>
      {ready.length > 0 && (
        <ul className="ready-list" aria-label={t('checklist.ready')}>
          {ready.map((s) => (
            <li key={s.slug} className="ready-item">
              <span className="badge badge-likely">
                <Icon name="check" size={16} /> {t('checklist.ready')}
              </span>
              <span>{t('checklist.ready_long', { scheme: schemeText(s, 'name', lang) })}</span>
            </li>
          ))}
        </ul>
      )}
      <ul className="check-list">
        {items.map((d, i) => {
          const doc = bundle.documents.documents[d.code] || {};
          const v = checklist.have[d.code];
          const name = pick(doc.name).text;
          return (
            <li className={`check-item ${v === true ? 'is-have' : ''}`} key={d.code}>
              <div className="check-head">
                <span className="check-num" aria-hidden="true">
                  {formatNumber(i + 1).padStart(2, formatNumber(0))}
                </span>
                <h3>
                  <span aria-hidden="true">{doc.icon || '📄'}</span> {name}{' '}
                  {!d.mandatory && <span className="small muted">({t('detail.optional')})</span>}
                </h3>
                <div className="seg" role="group" aria-label={name}>
                  <button type="button" aria-pressed={v === true} onClick={() => setHave(d.code, true)}>
                    {t('checklist.have')}
                  </button>
                  <button type="button" aria-pressed={v === false} onClick={() => setHave(d.code, false)}>
                    {t('checklist.dont_have')}
                  </button>
                </div>
              </div>
              <p className="small muted">{t('checklist.needed_for', { schemes: d.schemes.join(', ') })}</p>
              <details open={v === false}>
                <summary>{t('checklist.how_to')}</summary>
                <p>{pick(doc.how_to).text}</p>
                {doc.office && <p className="small">{t('checklist.office', { office: pick(doc.office).text })}</p>}
                {doc.portal_url && (
                  <p>
                    <a href={doc.portal_url} rel="noopener noreferrer" target="_blank">
                      {t('checklist.portal')} <Icon name="arrowUpRight" size={16} />
                    </a>
                  </p>
                )}
                {doc.source && !doc.source.verified_at && <p className="small muted">{t('source.not_verified')}</p>}
              </details>
            </li>
          );
        })}
      </ul>
      <p>
        <button className="btn btn-outline" type="button" onClick={clearChecklist}>
          {t('checklist.clear')}
        </button>
      </p>
    </div>
  );
}

export function Search({ query }) {
  const { t, lang, pick, formatNumber } = useI18n();
  const { bundle, result } = useApp();
  const [text, setText] = useState(query);
  const found = query ? searchSchemes(bundle.schemes, query, { lang, pick, categoryLabel: (c) => t(`cat.${c}`) }) : [];
  return (
    <div className="page">
      <PageHead title={t('search.title')} lead={t('search.note')} />
      <form
        className="search-form search-big"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          go(`#/search?q=${encodeURIComponent(text)}`);
        }}
      >
        <label className="visually-hidden" htmlFor="search-input">
          {t('search.label')}
        </label>
        <input id="search-input" type="search" value={text} placeholder={t('search.placeholder')} autoComplete="off" onChange={(e) => setText(e.target.value)} />
        <button className="btn btn-primary" type="submit">
          <Icon name="search" size={18} /> {t('search.button')}
        </button>
      </form>
      {query &&
        (found.length ? (
          <>
            <p role="status">{t('search.results', { n: formatNumber(found.length) })}</p>
            <ul className="card-grid">
              {found.map((s) => (
                <SchemeCard key={s.slug} scheme={s} result={result.results.find((r) => r.scheme === s.slug)} />
              ))}
            </ul>
          </>
        ) : (
          <Warn>{t('search.none')}</Warn>
        ))}
    </div>
  );
}

export function Help() {
  const { t } = useI18n();
  return (
    <div className="page">
      <PageHead kicker={t('nav.help')} title={t('help.title')} lead={t('help.body')} />
    </div>
  );
}

export function Privacy() {
  const { t } = useI18n();
  const { deleteAll, showNotice } = useApp();
  return (
    <div className="page">
      <PageHead kicker={t('footer.privacy')} title={t('privacy.title')} />
      <ol className="privacy-points">
        {['privacy.body1', 'privacy.body2', 'privacy.body3'].map((k) => (
          <li key={k}>{t(k)}</li>
        ))}
      </ol>
      <button
        className="btn btn-primary"
        type="button"
        onClick={() => {
          if (confirm(t('privacy.confirm'))) {
            deleteAll();
            showNotice(t('privacy.deleted'));
          }
        }}
      >
        {t('privacy.delete')}
      </button>
    </div>
  );
}
