// Reminders (brief §6.5). Switched on by the person; off by default.
// On the device: when the app opens, at most once a week, a notice says what is unfinished.
// Web Push: when the API has push keys and the person is signed in, the browser subscribes, so a
// reminder can arrive with the app closed. The push carries no data; the wording is saved here.
import { useEffect } from 'react';
import { useApp, KEYS } from './app-state.jsx';
import { useI18n } from './i18n-context.jsx';
import { useSession } from './session.jsx';
import { store } from './i18n.js';
import { PREFS_CACHE, reminderDue } from './schedule.js';

const today = () => new Date().toISOString().slice(0, 10);

export function readReminders() {
  try {
    return JSON.parse(store.get(KEYS.reminders) || 'null') || { enabled: false, last: null };
  } catch {
    return { enabled: false, last: null };
  }
}
export const writeReminders = (r) => store.set(KEYS.reminders, JSON.stringify(r));

export const canNotify = () => typeof Notification !== 'undefined' && 'serviceWorker' in navigator;

async function saveWording(title, body) {
  if (typeof caches === 'undefined') return;
  const cache = await caches.open(PREFS_CACHE);
  await cache.put('reminder-text', new Response(JSON.stringify({ title, body })));
}

const urlKey = (base64) => {
  const raw = atob(base64.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(base64.length / 4) * 4, '='));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
};

/** Subscribes this browser when the server can send pushes. Returns true if it did. */
async function subscribePush(session) {
  if (!session.signedIn || !canNotify() || !('PushManager' in window)) return false;
  const { public_key: publicKey } = await session.call('/reminders/push-key');
  if (!publicKey) return false;
  const registration = await navigator.serviceWorker.ready;
  const sub = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlKey(publicKey) });
  const { endpoint, keys } = sub.toJSON();
  await session.call('/me/reminders/push', { method: 'PUT', body: { endpoint, p256dh: keys.p256dh, auth: keys.auth } });
  return true;
}

async function unsubscribePush(session) {
  if (!canNotify()) return;
  const registration = await navigator.serviceWorker.getRegistration();
  const sub = await registration?.pushManager?.getSubscription();
  await sub?.unsubscribe();
  if (session.signedIn) await session.call('/me/reminders/push', { method: 'DELETE' }).catch(() => {});
}

/** @returns {Promise<'on' | 'on_in_app'>} 'on_in_app' when the browser won't show notifications. */
export async function enableReminders({ session, title, body }) {
  writeReminders({ enabled: true, last: today() });
  await saveWording(title, body).catch(() => {});
  if (!canNotify() || (await Notification.requestPermission()) !== 'granted') return 'on_in_app';
  await subscribePush(session).catch(() => {});
  return 'on';
}

export async function disableReminders({ session }) {
  writeReminders({ enabled: false, last: null });
  await unsubscribePush(session).catch(() => {});
}

/** Once per app start: shows what is unfinished if a reminder is due. */
export function useReminders() {
  const { checklist, schemeBy, members, showNotice } = useApp();
  const { t, formatNumber, formatDate } = useI18n();
  const state = members[0]?.profile.state;
  useEffect(() => {
    const saved = readReminders();
    const due = reminderDue(saved, { checklist, schemeBy, state, today: today() });
    if (!due) return;
    writeReminders({ ...saved, last: today() });
    const soonest = due.soon.map((x) => x.closes_at).sort()[0];
    showNotice(
      soonest ? t('remind.deadline', { date: formatDate(soonest) }) : t('remind.documents', { n: formatNumber(due.missing) }),
      12000,
    );
    // Only on the first render: a reminder belongs to opening the app, not to each tap.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}
