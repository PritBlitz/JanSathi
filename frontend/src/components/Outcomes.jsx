// What people reported about this scheme (brief §6.7). The server only answers once at least 10
// people have shared, so nobody can be picked out. Shown as experience, never as a scheme rule.
import { useEffect, useState } from 'react';
import { Reveal } from './Reveal.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { loadOutcomes } from '../lib/data.js';

export function Outcomes({ slug }) {
  const { t, formatNumber } = useI18n();
  const [data, setData] = useState(null);
  useEffect(() => {
    let live = true;
    loadOutcomes(slug).then((d) => live && setData(d));
    return () => {
      live = false;
    };
  }, [slug]);
  if (!data?.total || !data.reasons?.length) return null;
  return (
    <Reveal className="panel panel-track">
      <h2 className="panel-title">{t('outcomes.title')}</h2>
      <ul className="outcome-list">
        {data.reasons.map((r) => (
          <li key={r.code}>
            {t(`apps.code_${r.code}`)}: {formatNumber(r.count)}
          </li>
        ))}
      </ul>
      <p className="small muted">{t('outcomes.note', { n: formatNumber(data.total) })}</p>
    </Reveal>
  );
}
