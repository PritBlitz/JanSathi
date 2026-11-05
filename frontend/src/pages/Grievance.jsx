// Grievance form (DPDP Act 2023, PRIVACY §6): anyone can raise a complaint, without signing in.
// The contact is optional. The person keeps a reference to check progress later.
import { useState } from 'react';
import { PageHead } from '../components/SchemeParts.jsx';
import { Warn } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { api } from '../lib/session.jsx';
import { hasApi } from '../lib/data.js';

const CATEGORIES = ['privacy', 'data_deletion', 'helper_conduct', 'wrong_information', 'accessibility', 'other'];

export function Grievance() {
  const { t, lang } = useI18n();
  const [form, setForm] = useState({ category: '', message: '', contact: '' });
  const [sent, setSent] = useState(null);
  const [failed, setFailed] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function send(e) {
    e.preventDefault();
    setFailed(false);
    try {
      const r = await api('/grievances', { method: 'POST', body: { ...form, contact: form.contact || null, lang } });
      setSent(r.id);
    } catch {
      setFailed(true);
    }
  }

  return (
    <div className="page">
      <PageHead title={t('grievance.title')} lead={t('grievance.lead')} />
      {!hasApi() && <Warn>{t('grievance.unavailable')}</Warn>}
      {sent ? (
        <div role="status">
          <p className="label-strong">{t('grievance.sent')}</p>
          <p className="share-code-value">{sent}</p>
          <p className="small muted">{t('grievance.keep_id')}</p>
        </div>
      ) : (
        hasApi() && (
          <form className="track-form" onSubmit={send}>
            <label className="label-strong" htmlFor="g-category">
              {t('grievance.category')}
            </label>
            <select id="g-category" className="track-select" required value={form.category} onChange={set('category')}>
              <option value="" />
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {t(`grievance.cat_${c}`)}
                </option>
              ))}
            </select>
            <label className="label-strong" htmlFor="g-message">
              {t('grievance.message')}
            </label>
            <textarea id="g-message" className="track-input" rows={5} required maxLength={2000} value={form.message} onChange={set('message')} />
            <p className="small muted">{t('grievance.no_numbers')}</p>
            <label className="label-strong" htmlFor="g-contact">
              {t('grievance.contact')}
            </label>
            <input id="g-contact" className="track-input" maxLength={100} value={form.contact} onChange={set('contact')} />
            <button className="btn btn-primary" type="submit">
              {t('grievance.send')}
            </button>
            {failed && <Warn>{t('grievance.failed')}</Warn>}
          </form>
        )
      )}
    </div>
  );
}
