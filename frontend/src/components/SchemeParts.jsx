// Building blocks for scheme lists, results and the detail page.
import { Icon } from './Icon.jsx';
import { CheckedChip, Mark, ResultBadge, TrustChip, Warn, RESULT_KEY } from './Trust.jsx';
import { Reveal } from './Reveal.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSay } from '../lib/use-speech.js';
import { CATEGORY_ICONS, hostOf, langProps, schemeText } from '../lib/scheme.js';
import { readableText } from '../lib/text.js';

export function PageHead({ kicker, title, lead, children }) {
  return (
    <header className="page-head">
      {kicker && <p className="kicker">{kicker}</p>}
      <h1 className="display-title">{title}</h1>
      {lead && <p className="lead">{lead}</p>}
      {children}
    </header>
  );
}

/** Hard rules with their outcome for this profile, plus soft "may get priority" notes. */
export function Trace({ scheme, result }) {
  const { t, pick, lang } = useI18n();
  return (
    <>
      <ul className="trace">
        {scheme.rules
          .filter((rule) => rule.hard)
          .map((rule) => {
            const tr = result.rules.find((x) => x.code === rule.code);
            const text = pick(rule.text);
            return (
              <li key={rule.code}>
                <Mark outcome={tr.outcome} />
                <span>
                  <span className="visually-hidden">{t(`trace.${tr.outcome}`)}: </span>
                  <span lang={text.lang !== lang ? 'en' : undefined}>{text.text}</span>
                  {tr.outcome === 'fail' && rule.confidence === 'uncertain' && <span className="trace-note">{t('result.check_office')}</span>}
                </span>
              </li>
            );
          })}
      </ul>
      {scheme.rules
        .filter((rule) => !rule.hard && result.rules.find((x) => x.code === rule.code)?.outcome === 'pass')
        .map((rule) => (
          <p className="small" key={rule.code}>
            {t('result.priority', { rule: pick(rule.text).text })}
          </p>
        ))}
    </>
  );
}

/** A scheme card. With showTrace it is a results card: why it may or may not fit, what is missing. */
export function SchemeCard({ scheme: s, result: r, showTrace = false }) {
  const { t, lang } = useI18n();
  const { answered, skipped } = useApp();
  const say = useSay();
  const missing = showTrace && r.result === 'NEED_MORE_INFO' ? r.missing_fields.filter((f) => !skipped.includes(f)) : [];
  return (
    <Reveal as="li" className={`scheme-card ${answered ? `is-${RESULT_KEY[r.result]}` : ''}`}>
      <div className="scheme-card-top">
        <span className="scheme-icon" aria-hidden="true">
          {s.icon || CATEGORY_ICONS[s.category]}
        </span>
        <div className="badges">
          {answered && <ResultBadge result={r.result} />}
          <TrustChip label={r.label} />
          <CheckedChip scheme={s} />
        </div>
      </div>
      <h3 {...langProps(s, lang)}>
        <a className="stretched" href={`#/scheme/${s.slug}`}>
          {schemeText(s, 'name', lang)}
        </a>
      </h3>
      <p className="muted" {...langProps(s, lang)}>
        {schemeText(s, 'summary', lang)}
      </p>
      {showTrace && (
        <>
          {r.stale && <Warn>{t(s.status === 'published' ? 'trust.stale_warning' : 'trust.draft_warning')}</Warn>}
          <p className="label-strong">{t('result.because')}</p>
          <Trace scheme={s} result={r} />
          {missing.length > 0 && (
            <p className="small">
              <strong>{t('result.missing')}</strong> {missing.map((f) => t(`q.${f}`)).join(' · ')}
            </p>
          )}
        </>
      )}
      <div className="card-actions">
        <span className="card-more" aria-hidden="true">
          {t('result.details')} <Icon name="arrow" size={18} />
        </span>
        {showTrace && (
          <button className="btn btn-ghost btn-sm" type="button" onClick={(e) => say(readableText(e.currentTarget.closest('.scheme-card')))}>
            <Icon name="speaker" size={18} /> {t('result.read')}
          </button>
        )}
        {missing.length > 0 && (
          <a className="btn btn-ghost btn-sm" href="#/check">
            {t('result.answer_more')}
          </a>
        )}
      </div>
    </Reveal>
  );
}

export function SourceCard({ src, n }) {
  const { t, formatDate, formatNumber } = useI18n();
  const lines = [
    t(`doctype.${src.doc_type}`),
    src.published_date && t('source.published', { date: formatDate(src.published_date) }),
    src.fetched_at && t('source.fetched', { date: formatDate(src.fetched_at) }),
    src.verified_at ? t('source.verified', { date: formatDate(src.verified_at) }) : t('source.not_verified'),
  ].filter(Boolean);
  return (
    <li className="source-card" id={`src-${src.id}`}>
      <span className="source-num" aria-hidden="true">
        {formatNumber(n)}
      </span>
      <div>
        <p className="source-pub">{src.publisher}</p>
        <a href={src.url} rel="noopener noreferrer" target="_blank" lang="en">
          {src.title} <Icon name="arrowUpRight" size={16} />
        </a>
        <p className="source-host">{hostOf(src.url)}</p>
        <p className="source-meta">{lines.join(' · ')}</p>
      </div>
    </li>
  );
}

export function EmptyState({ title, body, action }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {body && <p className="muted">{body}</p>}
      {action}
    </div>
  );
}
