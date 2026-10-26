// Sign in with a mobile number and a one-time code. Only the last 4 digits are kept.
import { useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { PageHead, EmptyState } from '../components/SchemeParts.jsx';
import { Warn } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useSession, api } from '../lib/session.jsx';
import { go } from '../lib/router.js';

export function SignIn({ next }) {
  const { t, lang } = useI18n();
  const session = useSession();
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [step, setStep] = useState('phone');
  const [devCode, setDevCode] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const target = next && next.startsWith('#/') ? next : '#/guide';

  if (!session.available) {
    return (
      <div className="page">
        <PageHead title={t('auth.title')} />
        <EmptyState title={t('auth.unavailable')} />
      </div>
    );
  }
  if (session.signedIn) {
    return (
      <div className="page">
        <PageHead title={t('auth.title')} lead={t('auth.signed_in')} />
        <a className="btn btn-primary" href={target}>
          {t('nav.guide')} <Icon name="arrow" size={18} />
        </a>
      </div>
    );
  }

  async function sendCode(e) {
    e.preventDefault();
    setError('');
    // Same check as the server: a 10-digit Indian mobile number.
    if (!/^(?:\+?91|0)?[6-9]\d{9}$/.test(phone.replace(/[\s-]/g, ''))) {
      setError(t('auth.invalid_phone'));
      return;
    }
    setBusy(true);
    try {
      const r = await api('/auth/code', { method: 'POST', body: { phone, lang } });
      setDevCode(r.dev_code || null);
      setStep('code');
    } catch (err) {
      setError(t(err.status === 400 ? 'auth.too_many' : 'common.error'));
    } finally {
      setBusy(false);
    }
  }

  async function verify(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const r = await api('/auth/verify', { method: 'POST', body: { phone, code, lang } });
      session.signIn(r.token, r.user);
      go(target);
    } catch (err) {
      setError(t(err.status === 400 ? 'auth.wrong_code' : 'common.error'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page auth-page">
      <PageHead kicker={t('nav.sign_in')} title={t('auth.title')} lead={t('auth.lead')} />
      {step === 'phone' ? (
        <form className="auth-form" onSubmit={sendCode}>
          <label className="label-strong" htmlFor="phone">
            {t('auth.phone_label')}
          </label>
          <div className="search-form">
            <span className="phone-prefix" aria-hidden="true">
              +91
            </span>
            <input id="phone" type="tel" inputMode="numeric" autoComplete="tel-national" maxLength={14} required value={phone} onChange={(e) => setPhone(e.target.value)} />
            <button className="btn btn-primary" type="submit" disabled={busy}>
              {t('auth.send')}
            </button>
          </div>
        </form>
      ) : (
        <form className="auth-form" onSubmit={verify}>
          {devCode && <Warn>{t('auth.dev_code', { code: devCode })}</Warn>}
          <label className="label-strong" htmlFor="code">
            {t('auth.code_label')}
          </label>
          <div className="search-form">
            <input id="code" type="text" inputMode="numeric" autoComplete="one-time-code" maxLength={6} required value={code} onChange={(e) => setCode(e.target.value)} />
            <button className="btn btn-primary" type="submit" disabled={busy}>
              {t('auth.verify')}
            </button>
          </div>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => setStep('phone')}>
            {t('auth.change_number')}
          </button>
        </form>
      )}
      <p className="small q-status" role="status" aria-live="polite">
        {error}
      </p>
      <p className="privacy-note">{t('auth.privacy')}</p>
    </div>
  );
}
