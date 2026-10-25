// Office slip: the chosen schemes and the documents to carry, to show at a CSC or office.
// No personal details, so it is safe to print or share.
import { Icon } from '../components/Icon.jsx';
import { PageHead, EmptyState } from '../components/SchemeParts.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { hostOf, schemeText } from '../lib/scheme.js';

export function Slip() {
  const { t, lang, pick } = useI18n();
  const { bundle, checklist, schemeBy } = useApp();
  const schemes = checklist.schemes.map(schemeBy).filter(Boolean);

  if (!schemes.length) {
    return (
      <div className="page">
        <PageHead title={t('slip.title')} />
        <EmptyState title={t('checklist.empty')} />
      </div>
    );
  }

  const docs = new Map();
  for (const s of schemes) for (const d of s.documents) if (!docs.has(d.code) || d.mandatory) docs.set(d.code, d.mandatory || docs.get(d.code));
  const docName = (code) => pick(bundle.documents.documents[code]?.name).text;
  const text = [
    t('slip.title'),
    `${t('slip.schemes')}:`,
    ...schemes.map((s) => `- ${schemeText(s, 'name', lang)} (${s.official_apply_url})`),
    `${t('slip.documents')}:`,
    ...[...docs.entries()].map(([code, mandatory]) => `- ${docName(code)}${mandatory ? '' : ` (${t('detail.optional')})`}`),
    t('slip.footer'),
  ].join('\n');

  return (
    <div className="page slip">
      <PageHead title={t('slip.title')} lead={t('slip.lead')}>
        <div className="actions no-print">
          {navigator.share && (
            <button className="btn btn-primary" type="button" onClick={() => navigator.share({ title: t('slip.title'), text }).catch(() => {})}>
              {t('slip.share')}
            </button>
          )}
          <button className="btn btn-outline" type="button" onClick={() => window.print()}>
            {t('slip.print')}
          </button>
        </div>
      </PageHead>
      <section className="slip-section">
        <h2 className="panel-title">{t('slip.schemes')}</h2>
        <ol className="slip-list">
          {schemes.map((s) => (
            <li key={s.slug}>
              <strong>{schemeText(s, 'name', lang)}</strong>
              <span className="muted">
                {pick(s.ministry).text} · {hostOf(s.official_apply_url)}
              </span>
            </li>
          ))}
        </ol>
      </section>
      <section className="slip-section">
        <h2 className="panel-title">{t('slip.documents')}</h2>
        <ul className="slip-list slip-docs">
          {[...docs.entries()].map(([code, mandatory]) => (
            <li key={code}>
              <span className="slip-box" aria-hidden="true" />
              <span>
                {bundle.documents.documents[code]?.icon} {docName(code)}
                {!mandatory && <span className="muted"> ({t('detail.optional')})</span>}
              </span>
            </li>
          ))}
        </ul>
      </section>
      <p className="slip-footer">
        <Icon name="warn" size={16} /> {t('slip.footer')}
      </p>
    </div>
  );
}
