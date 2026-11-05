// My applications: the person's own record of what they applied for (brief §6.7).
import { Icon } from '../components/Icon.jsx';
import { PageHead, EmptyState } from '../components/SchemeParts.jsx';
import { TrackApplication } from '../components/TrackApplication.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSay } from '../lib/use-speech.js';
import { schemeText } from '../lib/scheme.js';

export function Applications() {
  const { t, lang, formatDate } = useI18n();
  const { applications, schemeBy, checklist } = useApp();
  const say = useSay();
  const tracked = Object.keys(applications).filter(schemeBy);
  const slugs = [...tracked, ...checklist.schemes.filter((s) => schemeBy(s) && !applications[s])];

  if (!slugs.length) {
    return (
      <div className="page">
        <PageHead kicker={t('nav.applications')} title={t('apps.title')} lead={t('apps.note')} />
        <EmptyState
          title={t('apps.empty')}
          action={
            <a className="btn btn-primary" href="#/schemes">
              {t('schemes.title')} <Icon name="arrow" size={18} />
            </a>
          }
        />
      </div>
    );
  }

  const readAll = () => say(tracked.map((slug) => `${schemeText(schemeBy(slug), 'name', lang)}: ${t(`apps.${applications[slug].status}`)}`).join('. ') || t('apps.note'));

  return (
    <div className="page">
      <PageHead kicker={t('nav.applications')} title={t('apps.title')} lead={t('apps.note')}>
        <div className="actions">
          <button className="btn btn-outline" type="button" onClick={readAll}>
            <Icon name="speaker" size={18} /> {t('result.read')}
          </button>
        </div>
      </PageHead>
      <ul className="application-list">
        {slugs.map((slug) => {
          const s = schemeBy(slug);
          const entry = applications[slug];
          return (
            <li className="check-item" key={slug}>
              <h2 className="application-name">
                <span aria-hidden="true">{s.icon}</span> <a href={`#/scheme/${slug}`}>{schemeText(s, 'name', lang)}</a>
              </h2>
              <TrackApplication slug={slug} plain />
              {entry && <p className="small muted">{t('apps.updated', { date: formatDate(entry.updated) })}</p>}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
