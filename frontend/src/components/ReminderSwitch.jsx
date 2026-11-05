// The on/off control for reminders, on the checklist page.
import { useState } from 'react';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSession } from '../lib/session.jsx';
import { disableReminders, enableReminders, readReminders } from '../lib/reminders.js';

export function ReminderSwitch() {
  const { t } = useI18n();
  const { showNotice } = useApp();
  const session = useSession();
  const [on, setOn] = useState(() => readReminders().enabled);
  const [busy, setBusy] = useState(false);

  async function toggle(next) {
    setBusy(true);
    setOn(next);
    if (next) {
      const how = await enableReminders({ session, title: t('remind.push_title'), body: t('remind.push_body') });
      showNotice(t(how === 'on' ? 'remind.enabled' : 'remind.enabled_in_app'));
    } else {
      await disableReminders({ session });
      showNotice(t('remind.disabled'));
    }
    setBusy(false);
  }

  return (
    <label className="reminder-switch">
      <input type="checkbox" checked={on} disabled={busy} onChange={(e) => toggle(e.target.checked)} />
      <span>
        {t('remind.label')}
        <span className="small muted reminder-note">{t('remind.note')}</span>
      </span>
    </label>
  );
}
