// My account: share my case with a helper (by code or by asking), see who looked (audit log)
// and their messages, stop sharing, sign out, delete everything.
import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { PageHead, EmptyState } from '../components/SchemeParts.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSession } from '../lib/session.jsx';
import { go } from '../lib/router.js';

const SCOPES = ['profile', 'checklist', 'conversation'];
const DURATIONS = [2, 24, 72, 168];

export function Account() {
  const { t, formatDate, formatNumber } = useI18n();
  const session = useSession();
  const { deleteAll, showNotice } = useApp();
  const [shares, setShares] = useState([]);
  const [notes, setNotes] = useState([]);
  const [audit, setAudit] = useState([]);
  const [scopes, setScopes] = useState(['profile', 'checklist']);
  const [hours, setHours] = useState(24);
  const [code, setCode] = useState(null);

  const load = useCallback(() => {
    session.call('/me/shares').then(setShares).catch(() => {});
    session.call('/me/notes').then(setNotes).catch(() => {});
    session.call('/me/audit').then(setAudit).catch(() => {});
  }, [session]);

  useEffect(() => {
    if (session.signedIn) load();
  }, [session.signedIn, load]);

  if (!session.signedIn) {
    return (
      <div className="page">
        <PageHead title={t('account.title')} />
        <EmptyState
          title={t(session.available ? 'auth.lead' : 'auth.unavailable')}
          action={
            session.available && (
              <a className="btn btn-primary" href="#/login?next=%23%2Faccount">
                {t('nav.sign_in')}
              </a>
            )
          }
        />
      </div>
    );
  }

  const when = (iso) => formatDate(String(iso).slice(0, 10));
  const durationLabel = (h) => (h < 24 ? t('share.hours', { n: formatNumber(h) }) : t('share.days', { n: formatNumber(h / 24) }));

  async function share(helpRequest) {
    const r = await session.call('/me/shares', { method: 'POST', body: { scopes, hours, help_request: helpRequest } }).catch(() => null);
    if (!r) return showNotice(t('common.error'));
    if (helpRequest) showNotice(t('share.asked'));
    else setCode(r);
    load();
  }

  return (
    <div className="page account-page">
      <PageHead kicker={t('nav.account')} title={t('account.title')} lead={t('account.number', { last4: session.user.phone_last4 })}>
        <div className="actions">
          {session.user.role === 'volunteer' && (
            <a className="btn btn-primary" href="#/helper">
              {t('nav.helper')} <Icon name="arrow" size={18} />
            </a>
          )}
          <button className="btn btn-outline" type="button" onClick={() => session.signOut().then(() => go('#/'))}>
            {t('account.sign_out')}
          </button>
        </div>
      </PageHead>

      <section className="panel account-section" aria-labelledby="share-title">
        <h2 className="panel-title" id="share-title">
          {t('share.title')}
        </h2>
        <p className="muted">{t('share.lead')}</p>
        <fieldset className="share-scopes">
          <legend className="label-strong">{t('share.what')}</legend>
          {SCOPES.map((s) => (
            <label key={s} className="toggle">
              <input
                type="checkbox"
                checked={scopes.includes(s)}
                onChange={(e) => setScopes(e.target.checked ? [...scopes, s] : scopes.filter((x) => x !== s))}
              />{' '}
              {t(`share.scope_${s}`)}
            </label>
          ))}
        </fieldset>
        <label className="label-strong" htmlFor="share-hours">
          {t('share.duration')}
        </label>
        <select id="share-hours" className="select" value={hours} onChange={(e) => setHours(Number(e.target.value))}>
          {DURATIONS.map((h) => (
            <option key={h} value={h}>
              {durationLabel(h)}
            </option>
          ))}
        </select>
        <div className="actions">
          <button className="btn btn-primary" type="button" disabled={!scopes.length} onClick={() => share(false)}>
            {t('share.create')}
          </button>
          <button className="btn btn-outline" type="button" disabled={!scopes.length} onClick={() => share(true)}>
            {t('share.ask')}
          </button>
        </div>
        {code && (
          <div className="share-code" role="status">
            <p className="label-strong">{t('share.code_title')}</p>
            <p className="share-code-value" aria-label={code.code.split('').join(' ')}>
              {code.code}
            </p>
            <p className="small muted">{t('share.code_note', { date: when(code.expires_at) })}</p>
          </div>
        )}
      </section>

      <section className="account-section" aria-labelledby="shares-title">
        <h2 className="group-title" id="shares-title">
          {t('share.active')}
        </h2>
        {!shares.length && <p className="muted">{t('audit.empty')}</p>}
        <ul className="household-list">
          {shares.map((s) => (
            <li key={s.id}>
              <span>
                <strong>{s.helper ? t('share.with', { helper: s.helper }) : t('share.waiting')}</strong>
                <span className="muted"> · {s.scopes.map((x) => t(`share.scope_${x}`)).join(', ')}</span>
              </span>
              <span className="muted">{t('share.until', { date: when(s.expires_at) })}</span>
              <button
                className="btn btn-ghost btn-sm"
                type="button"
                onClick={() => session.call(`/me/shares/${s.id}`, { method: 'DELETE' }).then(load)}
              >
                {t('share.stop')}
              </button>
            </li>
          ))}
        </ul>
      </section>

      {notes.length > 0 && (
        <section className="account-section" aria-labelledby="notes-title">
          <h2 className="group-title" id="notes-title">
            {t('notes.title')}
          </h2>
          <ul className="note-list">
            {notes.map((n) => (
              <li key={n.id} className="note">
                <p className="label-strong">
                  {n.helper} · <span className="muted">{when(n.created_at)}</span>
                </p>
                <p>{n.text}</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="account-section" aria-labelledby="audit-title">
        <h2 className="group-title" id="audit-title">
          {t('audit.title')}
        </h2>
        <ol className="audit-list">
          {audit.map((a, i) => (
            <li key={i}>
              <span className="muted">{when(a.at)}</span>
              <span>
                <strong>{a.actor === 'You' ? t('guide.you') : a.actor}</strong> {t(`audit.${a.action}`)}
              </span>
            </li>
          ))}
        </ol>
      </section>

      <section className="account-section">
        <button
          className="btn btn-outline"
          type="button"
          onClick={async () => {
            if (!confirm(t('account.delete_confirm'))) return;
            await session.call('/me', { method: 'DELETE' }).catch(() => {});
            deleteAll();
            await session.signOut();
            showNotice(t('account.deleted'));
            go('#/');
          }}
        >
          {t('account.delete')}
        </button>
      </section>
    </div>
  );
}
