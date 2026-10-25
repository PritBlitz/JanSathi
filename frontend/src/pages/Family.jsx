// Family mode (brief §6.1): one household, each member answers their own questions; household
// questions are asked once. Names stay on this device and can be anything ("Mother").
import { useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { PageHead } from '../components/SchemeParts.jsx';
import { ResultBadge } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { schemeText } from '../lib/scheme.js';
import { go } from '../lib/router.js';

export const memberName = (m, i, t) => m.name || (i === 0 ? t('family.you') : t('family.name_placeholder'));

/** Tabs to switch the member whose answers and results are shown. Hidden with one member. */
export function MemberSwitch({ labelKey }) {
  const { t } = useI18n();
  const { members, member, setActive } = useApp();
  if (members.length < 2) return null;
  return (
    <div className="member-switch" role="group" aria-label={t('nav.family')}>
      {members.map((m, i) => (
        <button key={m.id} type="button" className="filter-chip" aria-pressed={m.id === member.id} onClick={() => setActive(m.id)}>
          {t(labelKey, { name: memberName(m, i, t) })}
        </button>
      ))}
    </div>
  );
}

export function Family() {
  const { t, lang, formatNumber } = useI18n();
  const { members, resultFor, schemeBy, addMember, removeMember, setActive } = useApp();
  const [name, setName] = useState('');

  const results = members.map((m) => resultFor(m));
  // Whole household: every scheme that may fit at least one member, with who.
  const household = new Map();
  members.forEach((m, i) =>
    results[i].results
      .filter((r) => r.result === 'LIKELY_ELIGIBLE' && Object.keys(m.profile).length)
      .forEach((r) => household.set(r.scheme, [...(household.get(r.scheme) || []), memberName(m, i, t)])),
  );

  return (
    <div className="page">
      <PageHead kicker={t('nav.family')} title={t('family.title')} lead={t('family.lead')} />
      <ul className="member-grid">
        {members.map((m, i) => {
          const counts = { LIKELY_ELIGIBLE: 0, NEED_MORE_INFO: 0 };
          if (Object.keys(m.profile).length) results[i].results.forEach((r) => r.result in counts && counts[r.result]++);
          return (
            <li className="member-card" key={m.id}>
              <span className="member-avatar" aria-hidden="true">
                {memberName(m, i, t).slice(0, 1).toUpperCase()}
              </span>
              <h2 className="member-name">{memberName(m, i, t)}</h2>
              <div className="badges">
                {Object.entries(counts).map(([k, n]) => (
                  <span key={k} className="member-count">
                    <strong>{formatNumber(n)}</strong> <ResultBadge result={k} />
                  </span>
                ))}
              </div>
              <div className="card-actions">
                <button
                  className="btn btn-primary btn-sm"
                  type="button"
                  onClick={() => {
                    setActive(m.id);
                    go('#/check');
                  }}
                >
                  {t('family.answer_for', { name: memberName(m, i, t) })}
                </button>
                <button
                  className="btn btn-outline btn-sm"
                  type="button"
                  onClick={() => {
                    setActive(m.id);
                    go('#/results');
                  }}
                >
                  {t('family.results_for', { name: memberName(m, i, t) })}
                </button>
                {i > 0 && (
                  <button className="btn btn-ghost btn-sm" type="button" onClick={() => removeMember(m.id)}>
                    {t('family.remove')}
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>

      <form
        className="search-form member-add"
        onSubmit={(e) => {
          e.preventDefault();
          if (!name.trim()) return;
          addMember(name.trim().slice(0, 40));
          setName('');
        }}
      >
        <label className="label-strong" htmlFor="member-name">
          {t('family.name_label')}
        </label>
        <div className="search-form">
          <input id="member-name" value={name} placeholder={t('family.name_placeholder')} autoComplete="off" maxLength={40} onChange={(e) => setName(e.target.value)} />
          <button className="btn btn-primary" type="submit">
            {t('family.add')}
          </button>
        </div>
      </form>

      {household.size > 0 && (
        <section className="results-group" aria-labelledby="household-title">
          <h2 className="group-title" id="household-title">
            {t('family.household')}
          </h2>
          <ul className="household-list">
            {[...household.entries()].map(([slug, names]) => (
              <li key={slug}>
                <a href={`#/scheme/${slug}`}>{schemeText(schemeBy(slug), 'name', lang)}</a>
                <span className="muted">{names.join(', ')}</span>
                <Icon name="arrowUpRight" size={18} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
