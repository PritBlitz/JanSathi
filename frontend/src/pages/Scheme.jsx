// Scheme detail (DESIGN §7 bento): summary, benefit, how to apply, rules with clause citations,
// documents and numbered official sources. Every fact shown has a source on this page.
import { Icon } from '../components/Icon.jsx';
import { Reveal } from '../components/Reveal.jsx';
import { SourceCard, EmptyState } from '../components/SchemeParts.jsx';
import { CheckedChip, Mark, ResultBadge, TrustChip, Warn } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSay } from '../lib/use-speech.js';
import { REPORT_URL, inEnglish, langProps, schemeText } from '../lib/scheme.js';
import { reportApi } from '../lib/data.js';

export function Scheme({ slug }) {
  const { t, lang, pick, formatNumber, formatDate } = useI18n();
  const { bundle, result, schemeBy, answered, checklist, addToChecklist, showNotice } = useApp();
  const say = useSay();
  const s = schemeBy(slug);

  if (!s) {
    return (
      <div className="page">
        <EmptyState
          title={t('detail.not_found')}
          action={
            <a className="btn btn-outline" href="#/schemes">
              {t('schemes.title')}
            </a>
          }
        />
      </div>
    );
  }

  const r = result.results.find((x) => x.scheme === s.slug);
  const srcIndex = new Map(s.sources.map((src, i) => [src.id, i + 1]));
  const added = checklist.schemes.includes(s.slug);
  const text = (key) => schemeText(s, key, lang);

  function cite(id, locator) {
    const src = s.sources.find((x) => x.id === id);
    if (!src) return null;
    return (
      <a
        className="inline-cite"
        href={`#/scheme/${s.slug}`}
        onClick={(e) => {
          e.preventDefault();
          document.getElementById(`src-${id}`)?.scrollIntoView({ block: 'center' });
        }}
      >
        [{formatNumber(srcIndex.get(id))}] {src.publisher}
        {locator ? ` · ${t('source.locator', { locator })}` : ''}
      </a>
    );
  }

  function readAloud() {
    const checked = s.sources.find((x) => x.fetched_at);
    say(
      [
        text('name'),
        text('summary'),
        t('detail.benefit'),
        text('benefit'),
        t('detail.how'),
        text('how_to_apply'),
        s.status !== 'published' ? t('trust.draft_warning') : '',
        checked ? t('source.read', { publisher: checked.publisher, date: formatDate(checked.verified_at || checked.fetched_at) }) : '',
      ]
        .filter(Boolean)
        .join('. '),
    );
  }

  return (
    <div className="page">
      <a className="btn btn-ghost btn-sm back-link" href={answered ? '#/results' : '#/schemes'}>
        <Icon name="arrowLeft" size={18} /> {t(answered ? 'detail.back' : 'schemes.title')}
      </a>

      <header className="detail-head">
        <span className="detail-icon" aria-hidden="true">
          {s.icon}
        </span>
        <p className="kicker">{t(`cat.${s.category}`)}</p>
        <h1 className="display-title" {...langProps(s, lang)}>
          {text('name')}
        </h1>
        <p className="muted">{t('detail.ministry', { ministry: pick(s.ministry).text })}</p>
        <div className="badges">
          {answered && <ResultBadge result={r.result} />}
          <TrustChip label={r.label} />
          <CheckedChip scheme={s} />
        </div>
        {s.verification?.method === 'ai-dual-review' && <Warn>{t('trust.ai_checked_long', { date: formatDate(s.verification.date) })}</Warn>}
        {s.status !== 'published' ? <Warn>{t('trust.draft_warning')}</Warn> : r.stale && <Warn>{t('trust.stale_warning')}</Warn>}
        {inEnglish(s, lang) && <p className="small muted">{t('detail.in_english')}</p>}
        <div className="actions">
          <a className="btn btn-primary" href={s.official_apply_url} rel="noopener noreferrer" target="_blank">
            {t('detail.apply')} <Icon name="arrowUpRight" size={18} />
          </a>
          <button className="btn btn-outline" type="button" onClick={readAloud}>
            <Icon name="speaker" size={18} /> {t('detail.read')}
          </button>
        </div>
      </header>

      <div className="bento-detail">
        <Reveal className="panel panel-lead" {...langProps(s, lang)}>
          <p className="panel-summary">{text('summary')}</p>
        </Reveal>
        <Reveal className="panel panel-benefit" {...langProps(s, lang)}>
          <h2 className="panel-title">{t('detail.benefit')}</h2>
          <p>{text('benefit')}</p>
        </Reveal>
        <Reveal className="panel panel-how" {...langProps(s, lang)}>
          <h2 className="panel-title">{t('detail.how')}</h2>
          <p>{text('how_to_apply')}</p>
        </Reveal>
        <Reveal className="panel panel-rules">
          <h2 className="panel-title">{t('detail.rules')}</h2>
          <ul className="trace trace-detail">
            {s.rules
              .filter((rule) => rule.hard)
              .map((rule) => {
                const tr = r.rules.find((x) => x.code === rule.code);
                const rt = pick(rule.text);
                return (
                  <li key={rule.code}>
                    {answered ? <Mark outcome={tr.outcome} /> : <span className="mark mark-neutral" aria-hidden="true" />}
                    <span>
                      {answered && <span className="visually-hidden">{t(`trace.${tr.outcome}`)}: </span>}
                      <span lang={rt.lang !== lang ? 'en' : undefined}>{rt.text}</span>
                      <span className="trace-meta">
                        <TrustChip label={rule.confidence} /> {cite(rule.source, rule.locator)}
                      </span>
                    </span>
                  </li>
                );
              })}
          </ul>
        </Reveal>
        <Reveal className="panel panel-docs">
          <h2 className="panel-title">{t('detail.documents')}</h2>
          <ul className="doc-list">
            {s.documents.map((d) => {
              const doc = bundle.documents.documents[d.code];
              return (
                <li key={d.code}>
                  <span aria-hidden="true">{doc?.icon || '📄'}</span>
                  <span>
                    {pick(doc?.name).text}
                    {!d.mandatory && <span className="small muted"> ({t('detail.optional')})</span>}
                  </span>
                </li>
              );
            })}
          </ul>
          <button
            className={`btn ${added ? 'btn-outline' : 'btn-primary'} btn-block`}
            type="button"
            disabled={added}
            onClick={() => {
              addToChecklist(s.slug);
              showNotice(t('detail.added'), 3000);
            }}
          >
            {added ? (
              <>
                <Icon name="check" size={18} /> {t('detail.added')}
              </>
            ) : (
              t('detail.add_checklist')
            )}
          </button>
        </Reveal>
        <Reveal className="panel panel-sources">
          <h2 className="panel-title">{t('detail.sources')}</h2>
          <ol className="source-list">
            {s.sources.map((src, i) => (
              <SourceCard key={src.id} src={src} n={i + 1} />
            ))}
          </ol>
          <a
            className="btn btn-ghost btn-sm"
            rel="noopener"
            target="_blank"
            href={`${REPORT_URL}?title=${encodeURIComponent(`Outdated info: ${s.slug}`)}&labels=outdated-info`}
            onClick={async (e) => {
              // With the API, the report goes to the moderation queue; otherwise to a GitHub issue.
              e.preventDefault();
              const href = e.currentTarget.href;
              if (await reportApi({ scheme: s.slug, reason: 'outdated', lang })) showNotice(t('report.sent'));
              else window.open(href, '_blank', 'noopener');
            }}
          >
            <Icon name="warn" size={18} /> {t('detail.report')}
          </a>
        </Reveal>
      </div>
    </div>
  );
}
