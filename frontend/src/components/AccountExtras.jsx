// Parts of the account page: choosing how reminders arrive, and reporting a helper.
import { useCallback, useEffect, useState } from 'react';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSession } from '../lib/session.jsx';
import { enableReminders } from '../lib/reminders.js';

const HELPER_REASONS = ['asked_for_payment', 'misused_information', 'pretended_to_be_official', 'rude', 'other'];

export function ReminderChannels() {
  const { t } = useI18n();
  const { showNotice } = useApp();
  const session = useSession();
  const [available, setAvailable] = useState([]);
  const [mine, setMine] = useState([]);
  const [phone, setPhone] = useState('');
  const [link, setLink] = useState(null);

  const load = useCallback(() => {
    session.call('/reminders/channels').then((r) => setAvailable(r.channels)).catch(() => {});
    session.call('/me/reminders').then((r) => setMine(r.channels.map((c) => c.channel))).catch(() => {});
  }, [session]);
  useEffect(load, [load]);

  if (!available.length) return null;

  async function turnOn(channel) {
    try {
      if (channel === 'web_push') await enableReminders({ session, title: t('remind.push_title'), body: t('remind.push_body') });
      else if (channel === 'telegram') {
        const r = await session.call('/me/reminders/telegram', { method: 'POST' });
        setLink(r.link);
        return;
      } else await session.call(`/me/reminders/${channel}`, { method: 'PUT', body: { phone } });
      showNotice(t('remind.enabled'));
      load();
    } catch {
      showNotice(t('remind.channel_failed'));
    }
  }

  async function turnOff(channel) {
    await session.call(`/me/reminders/${channel}`, { method: 'DELETE' }).catch(() => {});
    showNotice(t('remind.disabled'));
    load();
  }

  const needsPhone = available.some((c) => (c === 'sms' || c === 'whatsapp') && !mine.includes(c));
  return (
    <section className="panel account-section" aria-labelledby="channels-title">
      <h2 className="panel-title" id="channels-title">
        {t('remind.channels_title')}
      </h2>
      <p className="muted">{t('remind.note')}</p>
      {needsPhone && (
        <>
          <label className="label-strong" htmlFor="reminder-phone">
            {t('auth.phone_label')}
          </label>
          <input id="reminder-phone" className="track-input" inputMode="numeric" maxLength={10} autoComplete="tel-national" value={phone} onChange={(e) => setPhone(e.target.value.replace(/\D/g, ''))} />
          <p className="small muted">{t('remind.phone_note')}</p>
        </>
      )}
      <ul className="channel-list">
        {available.map((c) => (
          <li key={c}>
            <span>{t(`remind.channel_${c}`)}</span>
            {mine.includes(c) ? (
              <button className="btn btn-ghost btn-sm" type="button" aria-label={`${t('remind.turn_off')}: ${t(`remind.channel_${c}`)}`} onClick={() => turnOff(c)}>
                {t('remind.turn_off')}
              </button>
            ) : (
              <button
                className="btn btn-outline btn-sm"
                type="button"
                disabled={(c === 'sms' || c === 'whatsapp') && phone.length !== 10}
                aria-label={`${t('remind.turn_on')}: ${t(`remind.channel_${c}`)}`}
                onClick={() => turnOn(c)}
              >
                {t('remind.turn_on')}
              </button>
            )}
          </li>
        ))}
      </ul>
      {link && (
        <p>
          <a className="btn btn-primary" href={link} rel="noopener noreferrer" target="_blank">
            {t('remind.telegram_open')}
          </a>
        </p>
      )}
    </section>
  );
}

export function ReportHelper() {
  const { t, formatDate } = useI18n();
  const { showNotice } = useApp();
  const session = useSession();
  const [helpers, setHelpers] = useState([]);
  const [share, setShare] = useState('');
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');

  useEffect(() => {
    session.call('/me/helpers').then(setHelpers).catch(() => {});
  }, [session]);
  if (!helpers.length) return null;

  async function send(e) {
    e.preventDefault();
    try {
      await session.call('/me/helper-reports', { method: 'POST', body: { share_id: share, reason, note } });
      setShare('');
      setReason('');
      setNote('');
      showNotice(t('helper_report.sent'));
    } catch {
      showNotice(t('helper_report.failed'));
    }
  }

  return (
    <section className="panel account-section" aria-labelledby="report-helper-title">
      <h2 className="panel-title" id="report-helper-title">
        {t('helper_report.title')}
      </h2>
      <p className="muted">{t('helper_report.lead')}</p>
      <form className="track-form" onSubmit={send}>
        <label className="label-strong" htmlFor="report-helper">
          {t('helper_report.who')}
        </label>
        <select id="report-helper" className="track-select" required value={share} onChange={(e) => setShare(e.target.value)}>
          <option value="" />
          {helpers.map((h) => (
            <option key={h.share_id} value={h.share_id}>
              {h.helper}
              {h.claimed_at ? ` · ${formatDate(h.claimed_at.slice(0, 10))}` : ''}
            </option>
          ))}
        </select>
        <label className="label-strong" htmlFor="report-reason">
          {t('helper_report.why')}
        </label>
        <select id="report-reason" className="track-select" required value={reason} onChange={(e) => setReason(e.target.value)}>
          <option value="" />
          {HELPER_REASONS.map((r) => (
            <option key={r} value={r}>
              {t(`helper_report.reason_${r}`)}
            </option>
          ))}
        </select>
        <label className="label-strong" htmlFor="report-note">
          {t('helper_report.note')}
        </label>
        <input id="report-note" className="track-input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
        <button className="btn btn-primary" type="submit">
          {t('helper_report.send')}
        </button>
      </form>
    </section>
  );
}
