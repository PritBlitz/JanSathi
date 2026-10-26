// The helper portal (brief §6.6): NGO and CSC helpers see only cases shared with them, only the
// parts allowed, only while the share lasts; every look is in the person's audit log.
import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { PageHead, EmptyState } from '../components/SchemeParts.jsx';
import { ResultBadge, TrustChip } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSession } from '../lib/session.jsx';
import { langName as nameOf } from '../lib/i18n.js';
import { answerLabel } from '../lib/labels.js';
import { schemeText } from '../lib/scheme.js';
import { go } from '../lib/router.js';

const ORDER = ['LIKELY_ELIGIBLE', 'NEED_MORE_INFO', 'NOT_ELIGIBLE'];

function Gate({ children }) {
  const { t } = useI18n();
  const session = useSession();
  if (!session.signedIn) {
    return (
      <div className="page">
        <PageHead kicker={t('nav.helper')} title={t('helper.title')} lead={t('helper.lead')} />
        {session.available ? (
          <a className="btn btn-primary" href="#/login?next=%23%2Fhelper">
            {t('nav.sign_in')}
          </a>
        ) : (
          <EmptyState title={t('auth.unavailable')} />
        )}
      </div>
    );
  }
  if (session.user.role !== 'volunteer') {
    return (
      <div className="page">
        <PageHead kicker={t('nav.helper')} title={t('helper.title')} />
        <EmptyState title={t('helper.not_helper')} />
      </div>
    );
  }
  return children;
}

export function Helper({ caseId }) {
  return <Gate>{caseId ? <CaseView id={caseId} /> : <Dashboard />}</Gate>;
}

function CaseRow({ c, action }) {
  const { t, formatDate } = useI18n();
  return (
    <li className="case-row">
      <div>
        <p className="label-strong">{t('helper.lang', { language: nameOf(c.lang || 'en') })}</p>
        <p className="small muted">
          {t('helper.can_see', { scopes: c.scopes.map((s) => t(`share.scope_${s}`)).join(', ') })} · {t('helper.until', { date: formatDate(c.expires_at.slice(0, 10)) })}
        </p>
      </div>
      {action}
    </li>
  );
}

function Dashboard() {
  const { t } = useI18n();
  const session = useSession();
  const { showNotice } = useApp();
  const [data, setData] = useState(null);
  const [code, setCode] = useState('');

  const load = useCallback(() => session.call('/volunteer').then(setData).catch(() => showNotice(t('common.error'))), [session, showNotice, t]);
  useEffect(() => {
    load();
  }, [load]);

  async function join(e) {
    e.preventDefault();
    try {
      const c = await session.call('/volunteer/join', { method: 'POST', body: { code } });
      go(`#/helper/${c.id}`);
    } catch {
      showNotice(t('helper.code_not_found'));
    }
  }

  return (
    <div className="page">
      <PageHead kicker={t('nav.helper')} title={t('helper.title')} lead={t('helper.lead')} />
      <form className="search-form" onSubmit={join}>
        <label className="visually-hidden" htmlFor="helper-code">
          {t('helper.join_label')}
        </label>
        <input id="helper-code" value={code} placeholder={t('helper.join_label')} autoComplete="off" maxLength={8} onChange={(e) => setCode(e.target.value.toUpperCase())} />
        <button className="btn btn-primary" type="submit">
          {t('helper.join')}
        </button>
      </form>

      <section className="results-group" aria-labelledby="req-title">
        <h2 className="group-title" id="req-title">
          {t('helper.requests')}
        </h2>
        {data && !data.open_requests.length && <p className="muted">{t('helper.requests_empty')}</p>}
        <ul className="case-list">
          {data?.open_requests.map((c) => (
            <CaseRow
              key={c.id}
              c={c}
              action={
                <button
                  className="btn btn-primary btn-sm"
                  type="button"
                  onClick={() => session.call(`/volunteer/requests/${c.id}/pick-up`, { method: 'POST' }).then(() => go(`#/helper/${c.id}`), load)}
                >
                  {t('helper.pick_up')}
                </button>
              }
            />
          ))}
        </ul>
      </section>

      <section className="results-group" aria-labelledby="cases-title">
        <h2 className="group-title" id="cases-title">
          {t('helper.my_cases')}
        </h2>
        {data && !data.my_cases.length && <p className="muted">{t('helper.my_cases_empty')}</p>}
        <ul className="case-list">
          {data?.my_cases.map((c) => (
            <CaseRow
              key={c.id}
              c={c}
              action={
                <a className="btn btn-outline btn-sm" href={`#/helper/${c.id}`}>
                  {t('helper.open')} <Icon name="arrow" size={16} />
                </a>
              }
            />
          ))}
        </ul>
      </section>
    </div>
  );
}

function CaseView({ id }) {
  const { t, lang, pick, formatNumber } = useI18n();
  const session = useSession();
  const { bundle, fields, schemeBy, showNotice } = useApp();
  const [view, setView] = useState(null);
  const [note, setNote] = useState('');
  const [chat, setChat] = useState([]);
  const [ask, setAsk] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(
    () =>
      session
        .call(`/volunteer/cases/${id}`)
        .then(setView)
        .catch(() => setView(false)),
    [session, id],
  );
  useEffect(() => {
    load();
  }, [load]);

  if (view === false) {
    return (
      <div className="page">
        <EmptyState
          title={t('helper.case_ended')}
          action={
            <a className="btn btn-outline" href="#/helper">
              {t('helper.back')}
            </a>
          }
        />
      </div>
    );
  }
  if (!view) return <div className="page page-loading" aria-busy="true" />;

  async function sendNote(e) {
    e.preventDefault();
    if (!note.trim()) return;
    await session.call(`/volunteer/cases/${id}/notes`, { method: 'POST', body: { text: note } }).catch(() => showNotice(t('common.error')));
    setNote('');
    load();
  }

  async function assist(e) {
    e.preventDefault();
    if (!ask.trim() || busy) return;
    const next = [...chat, { role: 'user', text: ask.trim() }];
    setChat(next);
    setAsk('');
    setBusy(true);
    try {
      const r = await session.call(`/volunteer/cases/${id}/assist`, { method: 'POST', body: { messages: next, lang } });
      setChat([...next, { role: 'guide', text: r.reply || t(r.fallback === 'no_model' ? 'guide.no_model_helper' : 'guide.unavailable') }]);
    } catch {
      setChat([...next, { role: 'guide', text: t('common.error') }]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <a className="btn btn-ghost btn-sm back-link" href="#/helper">
        <Icon name="arrowLeft" size={18} /> {t('helper.back')}
      </a>
      <PageHead kicker={t('helper.case')} title={t('helper.case')} lead={t('helper.can_see', { scopes: view.scopes.map((s) => t(`share.scope_${s}`)).join(', ') })}>
        <div className="actions">
          <button
            className="btn btn-outline btn-sm"
            type="button"
            onClick={() => session.call(`/volunteer/cases/${id}`, { method: 'DELETE' }).then(() => go('#/helper'))}
          >
            {t('helper.leave')}
          </button>
        </div>
      </PageHead>

      {view.members.map((m, i) => {
        const groups = Object.fromEntries(ORDER.map((k) => [k, m.results.filter((r) => r.result === k)]));
        return (
          <section className="panel helper-member" key={i}>
            <h2 className="panel-title">{m.name || `${t('helper.member')} ${formatNumber(i + 1)}`}</h2>
            <h3 className="label-strong">{t('helper.answers')}</h3>
            {Object.keys(m.profile).length === 0 ? (
              <p className="muted">{t('helper.no_answers')}</p>
            ) : (
              <dl className="answer-list">
                {Object.entries(m.profile).map(([f, v]) => (
                  <div key={f}>
                    <dt>{t(`q.${f}`)}</dt>
                    <dd>{answerLabel(fields, f, v, t, formatNumber)}</dd>
                  </div>
                ))}
              </dl>
            )}
            <h3 className="label-strong">{t('helper.results')}</h3>
            {ORDER.filter((k) => groups[k].length).map((k) => (
              <div key={k} className="helper-group">
                <ResultBadge result={k} />
                <ul>
                  {groups[k].map((r) => {
                    const s = schemeBy(r.scheme);
                    return (
                      <li key={r.scheme}>
                        <a href={`#/scheme/${r.scheme}`}>{s ? schemeText(s, 'name', lang) : r.scheme}</a> <TrustChip label={r.label} />
                        {r.missing_fields.length > 0 && (
                          <span className="small muted"> · {t('result.missing')} {r.missing_fields.map((f) => t(`q.${f}`)).join(' · ')}</span>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </section>
        );
      })}

      {view.checklist && (
        <section className="panel helper-member">
          <h2 className="panel-title">{t('helper.checklist')}</h2>
          {view.checklist.schemes.length === 0 && <p className="muted">{t('checklist.empty')}</p>}
          <ul className="doc-list">
            {[...new Set(view.checklist.schemes.flatMap((slug) => schemeBy(slug)?.documents.map((d) => d.code) || []))].map((code) => {
              const v = view.checklist.have[code];
              return (
                <li key={code}>
                  <span aria-hidden="true">{bundle.documents.documents[code]?.icon || '📄'}</span>
                  <span>
                    {pick(bundle.documents.documents[code]?.name).text}{' '}
                    <span className="small muted">({t(v === true ? 'checklist.have' : v === false ? 'checklist.dont_have' : 'helper.not_marked')})</span>
                  </span>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {view.conversation.length > 0 && (
        <section className="panel helper-member">
          <h2 className="panel-title">{t('helper.conversation')}</h2>
          <ol className="chat chat-small">
            {view.conversation.map((l, i) => (
              <li key={i} className={`bubble bubble-${l.role}`}>
                <span className="visually-hidden">{t(l.role === 'user' ? 'helper.person' : 'guide.saathi')}: </span>
                <p>{l.text}</p>
              </li>
            ))}
          </ol>
        </section>
      )}

      <section className="panel helper-member">
        <h2 className="panel-title">{t('helper.notes')}</h2>
        <ul className="note-list">
          {view.notes.map((n) => (
            <li key={n.id} className="note">
              <p className="label-strong">{n.helper}</p>
              <p>{n.text}</p>
            </li>
          ))}
        </ul>
        <form onSubmit={sendNote} className="note-form">
          <label className="visually-hidden" htmlFor="helper-note">
            {t('helper.notes')}
          </label>
          <textarea id="helper-note" rows={3} maxLength={1000} value={note} placeholder={t('helper.note_placeholder')} onChange={(e) => setNote(e.target.value)} />
          <button className="btn btn-primary" type="submit">
            {t('helper.send_note')}
          </button>
        </form>
      </section>

      <section className="panel helper-member">
        <h2 className="panel-title">{t('helper.assistant')}</h2>
        <ol className="chat chat-small" aria-live="polite">
          {chat.map((l, i) => (
            <li key={i} className={`bubble bubble-${l.role}`}>
              <p>{l.text}</p>
            </li>
          ))}
          {busy && <li className="bubble bubble-guide bubble-busy">{t('guide.thinking')}</li>}
        </ol>
        <form className="search-form" onSubmit={assist}>
          <label className="visually-hidden" htmlFor="helper-ask">
            {t('helper.assistant')}
          </label>
          <input id="helper-ask" value={ask} placeholder={t('helper.assistant_placeholder')} maxLength={600} onChange={(e) => setAsk(e.target.value)} />
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {t('guide.send')}
          </button>
        </form>
      </section>
    </div>
  );
}
