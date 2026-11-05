// When to apply and how a scheme sits with others (brief §6.1, §6.5). Everything comes from the
// scheme's content with a source; the matching rules are not touched here.
import { Reveal } from './Reveal.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { deadlinesFor, relationsAmong } from '../lib/schedule.js';
import { schemeText } from '../lib/scheme.js';

const today = () => new Date().toISOString().slice(0, 10);

export function Timeline({ scheme, cite }) {
  const { t, lang, pick, formatDate } = useI18n();
  const { members, schemeBy } = useApp();
  const deadlines = deadlinesFor(scheme, members[0]?.profile.state, today());
  const relations = (scheme.relations || []).filter((r) => schemeBy(r.with));
  return (
    <Reveal className="panel panel-when">
      <h2 className="panel-title">{t('window.title')}</h2>
      {deadlines.length > 0 ? (
        <ul className="deadline-list">
          {deadlines.map((d, i) => (
            <li key={i}>
              {d.label && <strong>{pick(d.label).text}: </strong>}
              {d.opens_at && t('window.opens', { date: formatDate(d.opens_at) })} {d.closes_at && t('window.closes', { date: formatDate(d.closes_at) })} {cite(d.source)}
            </li>
          ))}
        </ul>
      ) : (
        <p>{t(`window.${scheme.window?.type || 'unknown'}`)}</p>
      )}
      {relations.length > 0 && (
        <ul className="relation-list">
          {relations.map((r) => (
            <li key={`${r.kind}-${r.with}`}>
              <strong>{schemeText(schemeBy(r.with), 'name', lang)}:</strong> {r.note ? pick(r.note).text : t(`relation.${r.kind}`, { scheme: schemeText(schemeBy(r.with), 'name', lang) })} {cite(r.source)}
            </li>
          ))}
        </ul>
      )}
    </Reveal>
  );
}

/** On the results page: pairs of likely schemes that can or can't be held together. */
export function RelationNotes() {
  const { t, lang, pick } = useI18n();
  const { bundle, result, schemeBy } = useApp();
  const notes = relationsAmong(bundle.schemes, result.results);
  if (!notes.length) return null;
  return (
    <section className="relation-notes" aria-labelledby="relation-title">
      <h2 className="group-title" id="relation-title">
        {t('relation.title')}
      </h2>
      <ul>
        {notes.map((n) => (
          <li key={`${n.a}-${n.b}-${n.kind}`}>
            {n.note ? (
              <>
                <strong>
                  {schemeText(schemeBy(n.a), 'name', lang)} + {schemeText(schemeBy(n.b), 'name', lang)}:
                </strong>{' '}
                {pick(n.note).text}
              </>
            ) : (
              t(`relation.pair_${n.kind}`, { a: schemeText(schemeBy(n.a), 'name', lang), b: schemeText(schemeBy(n.b), 'name', lang) })
            )}{' '}
            {n.source && (
              <a href={n.source.url} rel="noopener noreferrer" target="_blank">
                {n.source.publisher}
              </a>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
