// What changed recently: official updates recorded in scheme content (`changes`), each with
// its source. Newest first. Shown in the nav only when at least one change exists.
import { PageHead, EmptyState } from '../components/SchemeParts.jsx';
import { Reveal } from '../components/Reveal.jsx';
import { Icon } from '../components/Icon.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { schemeText } from '../lib/scheme.js';

export const allChanges = (schemes) =>
  schemes
    .flatMap((s) => (s.changes || []).map((c) => ({ ...c, scheme: s })))
    .sort((a, b) => b.date.localeCompare(a.date));

export function Changes() {
  const { t, lang, pick, formatDate } = useI18n();
  const { bundle } = useApp();
  const list = allChanges(bundle.schemes);
  return (
    <div className="page">
      <PageHead kicker={t('nav.changes')} title={t('changes.title')} lead={t('changes.lead')} />
      {!list.length && <EmptyState title={t('detail.not_found')} />}
      <ol className="change-list">
        {list.map((c, i) => {
          const src = c.scheme.sources.find((x) => x.id === c.source);
          const text = pick(c.text);
          return (
            <Reveal as="li" className="change-item" key={`${c.scheme.slug}-${i}`}>
              <time className="change-date" dateTime={c.date}>
                {formatDate(c.date)}
              </time>
              <div>
                <h2 className="change-scheme">{schemeText(c.scheme, 'name', lang)}</h2>
                <p lang={text.lang !== lang ? 'en' : undefined}>{text.text}</p>
                <p className="source-meta">
                  {src && (
                    <a href={src.url} rel="noopener noreferrer" target="_blank" lang="en">
                      {src.publisher} <Icon name="arrowUpRight" size={14} />
                    </a>
                  )}{' '}
                  · <a href={`#/scheme/${c.scheme.slug}`}>{t('changes.see')}</a>
                </p>
              </div>
            </Reveal>
          );
        })}
      </ol>
    </div>
  );
}
