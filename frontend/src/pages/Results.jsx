// Results: three groups from the rules engine, each card with its rule trace (brief §6.1).
import { Icon } from '../components/Icon.jsx';
import { PageHead, SchemeCard, EmptyState } from '../components/SchemeParts.jsx';
import { RESULT_KEY, ResultBadge } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSay } from '../lib/use-speech.js';
import { schemeText } from '../lib/scheme.js';
import { go } from '../lib/router.js';
import { MemberSwitch } from './Family.jsx';
import { RelationNotes } from '../components/SchemeTimeline.jsx';

const ORDER = ['LIKELY_ELIGIBLE', 'NEED_MORE_INFO', 'NOT_ELIGIBLE'];

export function Results() {
  const { t, lang, formatNumber } = useI18n();
  const { profile, skipped, result, schemeBy, restart } = useApp();
  const say = useSay();

  if (!Object.keys(profile).length && !skipped.length) {
    return (
      <div className="page">
        <PageHead kicker={t('nav.schemes')} title={t('result.title')} />
        <MemberSwitch labelKey="family.results_for" />
        <EmptyState
          title={t('result.empty')}
          action={
            <a className="btn btn-primary btn-hero" href="#/check">
              {t('result.start')} <Icon name="arrow" size={20} />
            </a>
          }
        />
      </div>
    );
  }

  const groups = Object.fromEntries(ORDER.map((k) => [k, []]));
  for (const r of result.results) groups[r.result].push(r);
  const byName = (a, b) => schemeText(schemeBy(a.scheme), 'name', lang).localeCompare(schemeText(schemeBy(b.scheme), 'name', lang));
  const summary = t('result.summary_read', { likely: formatNumber(groups.LIKELY_ELIGIBLE.length), need: formatNumber(groups.NEED_MORE_INFO.length) });

  return (
    <div className="page">
      <PageHead kicker={t('nav.schemes')} title={t('result.title')} lead={t('result.lead')}>
        <div className="actions">
          <button className="btn btn-outline" type="button" onClick={() => say(summary)}>
            <Icon name="speaker" size={18} /> {t('result.read')}
          </button>
          {result.next_question && (
            <a className="btn btn-primary" href="#/check">
              {t('result.answer_more')}
            </a>
          )}
          <button
            className="btn btn-ghost"
            type="button"
            onClick={() => {
              restart();
              go('#/check');
            }}
          >
            {t('q.start_over')}
          </button>
        </div>
      </PageHead>

      <MemberSwitch labelKey="family.results_for" />
      <nav className="result-summary" aria-label={t('result.title')}>
        {ORDER.map((k) => (
          <a key={k} className={`summary-tile is-${RESULT_KEY[k]}`} href={`#/results`} onClick={(e) => {
            e.preventDefault();
            document.getElementById(`group-${RESULT_KEY[k]}`)?.scrollIntoView({ block: 'start' });
          }}>
            <span className="summary-num">{formatNumber(groups[k].length)}</span>
            <ResultBadge result={k} />
          </a>
        ))}
      </nav>

      <RelationNotes />

      {ORDER.filter((k) => groups[k].length).map((k) => (
        <section className="results-group" key={k} id={`group-${RESULT_KEY[k]}`} aria-labelledby={`group-title-${k}`}>
          <h2 className="group-title" id={`group-title-${k}`}>
            {t(`result.group.${RESULT_KEY[k]}`, { n: formatNumber(groups[k].length) })}
          </h2>
          <ul className="card-grid">
            {groups[k].sort(byName).map((r) => (
              <SchemeCard key={r.scheme} scheme={schemeBy(r.scheme)} result={r} showTrace />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
