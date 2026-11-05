// The reviewers' screens (#/admin): moderation queues, helpers, and a read-only view of sources and
// review state. Publishing is not here: content is code, and only tools/publish.mjs publishes, after
// two independent reviews. The sign-in token lives in sessionStorage, so it ends with the tab.
import { useCallback, useEffect, useState } from 'react';
import { PageHead, EmptyState } from '../components/SchemeParts.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { api } from '../lib/session.jsx';
import { hasApi } from '../lib/data.js';

const TOKEN_KEY = 'jansathi.admin';
const TABS = ['reports', 'grievances', 'helper_reports', 'helpers', 'sources'];
const ADMIN_TABS = ['staff', 'audit'];

function Field({ id, label, ...input }) {
  return (
    <div className="auth-form">
      <label className="label-strong" htmlFor={id}>
        {label}
      </label>
      <input id={id} autoComplete="off" {...input} />
    </div>
  );
}

function Status({ text }) {
  return (
    <p className="small q-status" role="status" aria-live="polite">
      {text}
    </p>
  );
}

function SignInForm({ onToken }) {
  const { t } = useI18n();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState('');

  async function submit(e) {
    e.preventDefault();
    setError('');
    try {
      const r = await api('/admin/auth/login', { method: 'POST', body: { username, password, code } });
      onToken(r.token);
    } catch {
      setError(t('admin.sign_in_failed'));
    }
  }

  return (
    <form className="auth-form" onSubmit={submit}>
      <Field id="admin-user" label={t('admin.username')} value={username} autoComplete="username" required onChange={(e) => setUsername(e.target.value)} />
      <Field id="admin-password" label={t('admin.password')} type="password" value={password} autoComplete="current-password" required onChange={(e) => setPassword(e.target.value)} />
      <Field id="admin-code" label={t('admin.code')} inputMode="numeric" maxLength={6} value={code} required onChange={(e) => setCode(e.target.value)} />
      <button className="btn btn-primary" type="submit">
        {t('admin.sign_in')}
      </button>
      <Status text={error} />
    </form>
  );
}

/** A new account: the set-up code from an admin, a password, then a first code from the authenticator app. */
function SetupForm() {
  const { t } = useI18n();
  const [username, setUsername] = useState('');
  const [setupCode, setSetupCode] = useState('');
  const [password, setPassword] = useState('');
  const [secret, setSecret] = useState(null);
  const [code, setCode] = useState('');
  const [message, setMessage] = useState('');

  async function choose(e) {
    e.preventDefault();
    setMessage('');
    try {
      const r = await api('/admin/auth/setup', { method: 'POST', body: { username, setup_code: setupCode, password } });
      setSecret(r.totp_secret);
    } catch (err) {
      setMessage(err.message);
    }
  }

  async function confirm(e) {
    e.preventDefault();
    setMessage('');
    try {
      await api('/admin/auth/confirm', { method: 'POST', body: { username, setup_code: setupCode, code } });
      setSecret(null);
      setMessage(t('admin.setup_done'));
    } catch (err) {
      setMessage(err.message);
    }
  }

  if (secret) {
    return (
      <form className="auth-form" onSubmit={confirm}>
        <p>{t('admin.setup_secret')}</p>
        <p className="label-strong">
          <code>{secret}</code>
        </p>
        <Field id="setup-first-code" label={t('admin.code')} inputMode="numeric" maxLength={6} value={code} required onChange={(e) => setCode(e.target.value)} />
        <button className="btn btn-primary" type="submit">
          {t('admin.setup_confirm')}
        </button>
        <Status text={message} />
      </form>
    );
  }
  return (
    <form className="auth-form" onSubmit={choose}>
      <Field id="setup-user" label={t('admin.username')} value={username} required onChange={(e) => setUsername(e.target.value)} />
      <Field id="setup-code" label={t('admin.setup_code')} value={setupCode} required onChange={(e) => setSetupCode(e.target.value)} />
      <Field id="setup-password" label={t('admin.new_password')} type="password" minLength={12} value={password} autoComplete="new-password" required onChange={(e) => setPassword(e.target.value)} />
      <button className="btn btn-primary" type="submit">
        {t('admin.setup_next')}
      </button>
      <Status text={message} />
    </form>
  );
}

function BootstrapForm({ onToken }) {
  const { t } = useI18n();
  const [value, setValue] = useState('');
  return (
    <form
      className="auth-form"
      onSubmit={(e) => {
        e.preventDefault();
        onToken(value.trim());
      }}
    >
      <p className="small muted">{t('admin.bootstrap_note')}</p>
      <Field id="admin-bootstrap" label={t('admin.bootstrap_token')} type="password" value={value} required onChange={(e) => setValue(e.target.value)} />
      <button className="btn btn-ghost btn-sm" type="submit">
        {t('admin.sign_in')}
      </button>
    </form>
  );
}

function Gate({ onToken, failed }) {
  const { t } = useI18n();
  const [mode, setMode] = useState('sign_in');
  return (
    <div className="page auth-page">
      <PageHead kicker={t('admin.kicker')} title={t('admin.title')} lead={t('admin.lead')} />
      {mode === 'sign_in' && <SignInForm onToken={onToken} />}
      {mode === 'setup' && <SetupForm />}
      {mode === 'bootstrap' && <BootstrapForm onToken={onToken} />}
      {failed && <Status text={t('admin.sign_in_failed')} />}
      <p>
        {['sign_in', 'setup', 'bootstrap']
          .filter((m) => m !== mode)
          .map((m) => (
            <button key={m} className="btn btn-ghost btn-sm" type="button" onClick={() => setMode(m)}>
              {t(`admin.mode_${m}`)}
            </button>
          ))}
      </p>
    </div>
  );
}

/** One moderation queue. `row` draws an item; `body` turns the text box into the resolve request. */
function Queue({ call, path, title, row, body, extra }) {
  const { t, formatDate } = useI18n();
  const { showNotice } = useApp();
  const [status, setStatus] = useState('open');
  const [items, setItems] = useState(null);
  const [text, setText] = useState({});
  const [flag, setFlag] = useState({});

  const load = useCallback(
    () =>
      call(`${path}?status=${status}`)
        .then(setItems)
        .catch(() => showNotice(t('common.error'))),
    [call, path, status, showNotice, t],
  );
  useEffect(() => {
    load();
  }, [load]);

  async function close(id, outcome) {
    try {
      await call(`${path}/${id}/resolve`, { method: 'POST', body: body(outcome, text[id] || '', !!flag[id]) });
      load();
    } catch (e) {
      showNotice(e.message || t('common.error'));
    }
  }

  return (
    <section className="results-group" aria-label={title}>
      <h2 className="group-title">{title}</h2>
      <p>
        {['open', 'resolved', 'rejected'].map((s) => (
          <button key={s} className={`btn btn-sm ${s === status ? 'btn-primary' : 'btn-ghost'}`} type="button" aria-pressed={s === status} onClick={() => setStatus(s)}>
            {t(`admin.status_${s}`)}
          </button>
        ))}
      </p>
      {items && !items.length && <p className="muted">{t('admin.queue_empty')}</p>}
      <ul className="case-list">
        {items?.map((item) => (
          <li className="case-row" key={item.id}>
            <div>
              {row(item)}
              <p className="small muted">{formatDate((item.received_at || '').slice(0, 10))}</p>
              {item.status !== 'open' && (
                <p className="small">
                  {item.resolution || item.response} {item.resolved_by ? `(${item.resolved_by})` : ''}
                </p>
              )}
              {item.status === 'open' && (
                <>
                  <label className="visually-hidden" htmlFor={`note-${item.id}`}>
                    {t('admin.response')}
                  </label>
                  <textarea id={`note-${item.id}`} rows={2} maxLength={1000} placeholder={t('admin.response')} value={text[item.id] || ''} onChange={(e) => setText({ ...text, [item.id]: e.target.value })} />
                  {extra && (
                    <label className="small">
                      <input type="checkbox" checked={!!flag[item.id]} onChange={(e) => setFlag({ ...flag, [item.id]: e.target.checked })} /> {extra}
                    </label>
                  )}
                  <p>
                    <button className="btn btn-primary btn-sm" type="button" onClick={() => close(item.id, 'resolved')}>
                      {t('admin.resolve')}
                    </button>
                    <button className="btn btn-ghost btn-sm" type="button" onClick={() => close(item.id, 'rejected')}>
                      {t('admin.reject')}
                    </button>
                  </p>
                </>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Reports({ call }) {
  const { t } = useI18n();
  return (
    <Queue
      call={call}
      path="/admin/reports"
      title={t('admin.tab_reports')}
      body={(status, resolution) => ({ status, resolution })}
      row={(r) => (
        <>
          <p className="label-strong">
            {r.scheme}: {t(`admin.report_${r.reason}`)}
          </p>
          {r.note && <p>{r.note}</p>}
        </>
      )}
    />
  );
}

function Grievances({ call }) {
  const { t } = useI18n();
  return (
    <Queue
      call={call}
      path="/admin/grievances"
      title={t('admin.tab_grievances')}
      body={(status, response) => ({ status, response })}
      row={(g) => (
        <>
          <p className="label-strong">{t(`admin.grievance_${g.category}`)}</p>
          <p>{g.message}</p>
          {g.contact && <p className="small">{t('admin.contact', { contact: g.contact })}</p>}
        </>
      )}
    />
  );
}

function HelperReports({ call, isAdmin }) {
  const { t } = useI18n();
  return (
    <Queue
      call={call}
      path="/admin/helper-reports"
      title={t('admin.tab_helper_reports')}
      extra={isAdmin ? t('admin.deactivate_too') : null}
      body={(status, resolution, deactivate) => ({ status, resolution, deactivate_helper: deactivate })}
      row={(r) => (
        <>
          <p className="label-strong">
            {r.helper}: {t(`admin.helper_reason_${r.reason}`)}
          </p>
          {r.note && <p>{r.note}</p>}
        </>
      )}
    />
  );
}

function Helpers({ call, isAdmin }) {
  const { t, formatDate } = useI18n();
  const { showNotice } = useApp();
  const [items, setItems] = useState(null);
  const [form, setForm] = useState({ phone: '', name: '', organisation: '' });
  const load = useCallback(
    () =>
      call('/admin/volunteers?include_deactivated=true')
        .then(setItems)
        .catch(() => showNotice(t('common.error'))),
    [call, showNotice, t],
  );
  useEffect(() => {
    load();
  }, [load]);

  async function add(e) {
    e.preventDefault();
    try {
      await call('/admin/volunteers', { method: 'POST', body: form });
      setForm({ phone: '', name: '', organisation: '' });
      load();
    } catch (err) {
      showNotice(err.message || t('common.error'));
    }
  }

  return (
    <section className="results-group" aria-label={t('admin.tab_helpers')}>
      <h2 className="group-title">{t('admin.tab_helpers')}</h2>
      <p className="small muted">{t('admin.helpers_note')}</p>
      {isAdmin && (
        <form className="auth-form" onSubmit={add}>
          <Field id="helper-phone" label={t('admin.helper_phone')} inputMode="tel" value={form.phone} required onChange={(e) => setForm({ ...form, phone: e.target.value })} />
          <Field id="helper-name" label={t('admin.helper_name')} maxLength={80} value={form.name} required onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <Field id="helper-org" label={t('admin.helper_org')} maxLength={120} value={form.organisation} required onChange={(e) => setForm({ ...form, organisation: e.target.value })} />
          <button className="btn btn-primary btn-sm" type="submit">
            {t('admin.helper_add')}
          </button>
        </form>
      )}
      <ul className="case-list">
        {items?.map((h) => (
          <li className="case-row" key={h.id}>
            <div>
              <p className="label-strong">
                {h.display_name} ({h.organisation})
              </p>
              <p className="small muted">
                {t('admin.phone_ends', { digits: h.phone_last4 })}
                {h.last_seen_at ? ` · ${t('admin.last_seen', { date: formatDate(h.last_seen_at.slice(0, 10)) })}` : ''}
                {h.deactivated_at ? ` · ${t(`admin.deactivated_${h.deactivated_reason}`)}` : ''}
              </p>
            </div>
            {isAdmin && !h.deactivated_at && (
              <button className="btn btn-ghost btn-sm" type="button" aria-label={t('admin.helper_deactivate_named', { name: h.display_name })} onClick={() => call(`/admin/volunteers/${h.id}`, { method: 'DELETE' }).then(load, () => showNotice(t('common.error')))}>
                {t('admin.helper_deactivate')}
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function Sources({ call }) {
  const { t, formatDate } = useI18n();
  const { showNotice } = useApp();
  const [data, setData] = useState(null);
  useEffect(() => {
    call('/admin/sources')
      .then(setData)
      .catch(() => showNotice(t('common.error')));
  }, [call, showNotice, t]);
  return (
    <section className="results-group" aria-label={t('admin.tab_sources')}>
      <h2 className="group-title">{t('admin.tab_sources')}</h2>
      <p className="privacy-note">{t('admin.publish_note')}</p>
      <ul className="case-list">
        {data?.schemes.map((s) => (
          <li className="case-row" key={s.slug}>
            <div>
              <p className="label-strong">
                {s.slug}: {t(`admin.scheme_${s.status}`)}
              </p>
              <p className="small muted">
                {s.reviewed_on ? t('admin.reviewed', { date: formatDate(s.reviewed_on), who: s.reviewers.join(', ') }) : t('admin.not_reviewed')}
                {s.reverify_due ? ` · ${t(s.overdue ? 'admin.reverify_overdue' : 'admin.reverify_due', { date: formatDate(s.reverify_due) })}` : ''}
              </p>
              <ul>
                {s.sources
                  .filter((x) => x.check_status !== 'ok')
                  .map((x) => (
                    <li className="small" key={x.id}>
                      <a href={x.url} target="_blank" rel="noreferrer">
                        {x.title || x.id}
                      </a>
                      : {x.check_status} {x.since ? `(${formatDate(x.since)})` : ''} {x.note}
                    </li>
                  ))}
              </ul>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Staff({ call }) {
  const { t } = useI18n();
  const { showNotice } = useApp();
  const [items, setItems] = useState(null);
  const [form, setForm] = useState({ username: '', display_name: '', role: 'reviewer' });
  const [issued, setIssued] = useState(null);
  const load = useCallback(
    () =>
      call('/admin/staff')
        .then(setItems)
        .catch(() => showNotice(t('common.error'))),
    [call, showNotice, t],
  );
  useEffect(() => {
    load();
  }, [load]);

  async function run(promise) {
    try {
      const r = await promise;
      if (r?.setup_code) setIssued({ username: r.staff.username, code: r.setup_code });
      load();
    } catch (e) {
      showNotice(e.message || t('common.error'));
    }
  }

  return (
    <section className="results-group" aria-label={t('admin.tab_staff')}>
      <h2 className="group-title">{t('admin.tab_staff')}</h2>
      {issued && (
        <p className="privacy-note" role="status">
          {t('admin.setup_issued', { username: issued.username })} <code>{issued.code}</code>
        </p>
      )}
      <form
        className="auth-form"
        onSubmit={(e) => {
          e.preventDefault();
          run(call('/admin/staff', { method: 'POST', body: form }));
        }}
      >
        <Field id="staff-user" label={t('admin.username')} value={form.username} required onChange={(e) => setForm({ ...form, username: e.target.value })} />
        <Field id="staff-name" label={t('admin.staff_name')} maxLength={80} value={form.display_name} required onChange={(e) => setForm({ ...form, display_name: e.target.value })} />
        <label className="label-strong" htmlFor="staff-role">
          {t('admin.staff_role')}
        </label>
        <select id="staff-role" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
          <option value="reviewer">{t('admin.role_reviewer')}</option>
          <option value="admin">{t('admin.role_admin')}</option>
        </select>
        <button className="btn btn-primary btn-sm" type="submit">
          {t('admin.staff_add')}
        </button>
      </form>
      <ul className="case-list">
        {items?.map((s) => (
          <li className="case-row" key={s.id}>
            <div>
              <p className="label-strong">
                {s.display_name} ({s.username})
              </p>
              <p className="small muted">
                {t(`admin.role_${s.role}`)} · {t(!s.active ? 'admin.staff_inactive' : s.ready ? 'admin.staff_ready' : 'admin.staff_pending')}
              </p>
            </div>
            {s.active && (
              <p>
                <button className="btn btn-ghost btn-sm" type="button" aria-label={t('admin.staff_reset_named', { name: s.username })} onClick={() => run(call(`/admin/staff/${s.id}/reset`, { method: 'POST' }))}>
                  {t('admin.staff_reset')}
                </button>
                <button className="btn btn-ghost btn-sm" type="button" aria-label={t('admin.staff_deactivate_named', { name: s.username })} onClick={() => run(call(`/admin/staff/${s.id}`, { method: 'DELETE' }))}>
                  {t('admin.helper_deactivate')}
                </button>
              </p>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function Audit({ call }) {
  const { t, formatDate } = useI18n();
  const { showNotice } = useApp();
  const [items, setItems] = useState(null);
  useEffect(() => {
    call('/admin/audit')
      .then(setItems)
      .catch(() => showNotice(t('common.error')));
  }, [call, showNotice, t]);
  return (
    <section className="results-group" aria-label={t('admin.tab_audit')}>
      <h2 className="group-title">{t('admin.tab_audit')}</h2>
      <ul className="case-list">
        {items?.map((a, i) => (
          <li className="case-row" key={`${a.at}-${i}`}>
            <p className="small">
              {formatDate(a.at.slice(0, 10))} · {a.actor} · <code>{a.action}</code> {a.target ? `· ${a.target}` : ''} · {a.status}
            </p>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function Admin() {
  const { t } = useI18n();
  const [token, setToken] = useState(() => sessionStorage.getItem(TOKEN_KEY));
  const [me, setMe] = useState(null);
  const [failed, setFailed] = useState(false);
  const [tab, setTab] = useState('reports');

  const signOut = useCallback(() => {
    sessionStorage.removeItem(TOKEN_KEY);
    setToken(null);
    setMe(null);
  }, []);

  useEffect(() => {
    if (!token || !hasApi()) return;
    api('/admin/me', { token })
      .then((m) => {
        setMe(m);
        setFailed(false);
      })
      .catch(() => {
        setFailed(true);
        signOut();
      });
  }, [token, signOut]);

  const call = useCallback((path, opts = {}) => api(path, { ...opts, token }), [token]);

  if (!hasApi()) {
    return (
      <div className="page">
        <PageHead kicker={t('admin.kicker')} title={t('admin.title')} />
        <EmptyState title={t('admin.unavailable')} />
      </div>
    );
  }
  if (!me) {
    return (
      <Gate
        failed={failed}
        onToken={(next) => {
          sessionStorage.setItem(TOKEN_KEY, next);
          setToken(next);
        }}
      />
    );
  }

  const isAdmin = me.role === 'admin';
  return (
    <div className="page">
      <PageHead kicker={t('admin.kicker')} title={t('admin.title')} lead={t('admin.signed_in_as', { name: me.label })}>
        <button
          className="btn btn-ghost btn-sm"
          type="button"
          onClick={() => {
            api('/admin/auth/logout', { method: 'POST', token }).catch(() => {});
            signOut();
          }}
        >
          {t('admin.sign_out')}
        </button>
      </PageHead>
      <nav aria-label={t('admin.sections')}>
        {[...TABS, ...(isAdmin ? ADMIN_TABS : [])].map((x) => (
          <button key={x} className={`btn btn-sm ${x === tab ? 'btn-primary' : 'btn-ghost'}`} type="button" aria-pressed={x === tab} onClick={() => setTab(x)}>
            {t(`admin.tab_${x}`)}
          </button>
        ))}
      </nav>
      {tab === 'reports' && <Reports call={call} />}
      {tab === 'grievances' && <Grievances call={call} />}
      {tab === 'helper_reports' && <HelperReports call={call} isAdmin={isAdmin} />}
      {tab === 'helpers' && <Helpers call={call} isAdmin={isAdmin} />}
      {tab === 'sources' && <Sources call={call} />}
      {tab === 'staff' && <Staff call={call} />}
      {tab === 'audit' && <Audit call={call} />}
    </div>
  );
}
