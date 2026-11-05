// Manual application tracking on a scheme page (brief §6.7). The person writes down where their
// application stands; nothing here talks to a government system.
import { useState } from 'react';
import { Reveal } from './Reveal.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { APPLICATION_STATUSES, REASON_CODES } from '../lib/schedule.js';
import { hasApi, shareOutcome } from '../lib/data.js';

export function TrackApplication({ slug, plain = false }) {
  const { t } = useI18n();
  const { applications, trackApplication, stopTracking } = useApp();
  const entry = applications[slug];
  const [reason, setReason] = useState(entry?.reason || '');
  const [code, setCode] = useState('');
  const [shared, setShared] = useState(false);
  const id = `track-${slug}`;

  const body = (
    <>
      {!plain && <h2 className="panel-title">{t('apps.track')}</h2>}
      <label className="label-strong" htmlFor={id}>
        {t('apps.status')}
      </label>
      <select
        id={id}
        className="track-select"
        value={entry?.status || ''}
        onChange={(e) => (e.target.value ? trackApplication(slug, { status: e.target.value, reason }) : stopTracking(slug))}
      >
        <option value="">{t('apps.not_tracking')}</option>
        {APPLICATION_STATUSES.map((s) => (
          <option key={s} value={s}>
            {t(`apps.${s}`)}
          </option>
        ))}
      </select>
      {entry?.status === 'rejected' && (
        <>
          <label className="label-strong" htmlFor={`${id}-why`}>
            {t('apps.reason')}
          </label>
          <input
            id={`${id}-why`}
            className="track-input"
            maxLength={300}
            value={reason}
            placeholder={t('apps.reason_hint')}
            onChange={(e) => setReason(e.target.value)}
            onBlur={() => trackApplication(slug, { status: 'rejected', reason })}
          />
        </>
      )}
      {hasApi() && (entry?.status === 'rejected' || entry?.status === 'approved') && !shared && (
        <div className="outcome-share">
          {entry.status === 'rejected' && (
            <>
              <label className="label-strong" htmlFor={`${id}-code`}>
                {t('apps.code_label')}
              </label>
              <select id={`${id}-code`} className="track-select" value={code} onChange={(e) => setCode(e.target.value)}>
                <option value="">{t('apps.not_tracking')}</option>
                {REASON_CODES.map((c) => (
                  <option key={c} value={c}>
                    {t(`apps.code_${c}`)}
                  </option>
                ))}
              </select>
            </>
          )}
          <button
            className="btn btn-ghost btn-sm"
            type="button"
            disabled={entry.status === 'rejected' && !code}
            onClick={async () => setShared(await shareOutcome({ scheme: slug, status: entry.status, reason_code: code || null }))}
          >
            {t('apps.share_outcome')}
          </button>
          <p className="small muted">{t('apps.share_note')}</p>
        </div>
      )}
      {!plain && <p className="small muted">{t('apps.note')}</p>}
    </>
  );
  return plain ? <div className="track-form">{body}</div> : <Reveal className="panel panel-track">{body}</Reveal>;
}
