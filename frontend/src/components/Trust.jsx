// Result badges and trust chips (DESIGN §3.3): state is always icon + text + colour, never colour alone.
import { Icon } from './Icon.jsx';
import { useI18n } from '../lib/i18n-context.jsx';

export const RESULT_KEY = { LIKELY_ELIGIBLE: 'likely', NEED_MORE_INFO: 'need_info', NOT_ELIGIBLE: 'not_eligible' };
const RESULT_ICON = { LIKELY_ELIGIBLE: 'check', NEED_MORE_INFO: 'question', NOT_ELIGIBLE: 'dash' };

export function ResultBadge({ result }) {
  const { t } = useI18n();
  return (
    <span className={`badge badge-${RESULT_KEY[result]}`}>
      <Icon name={RESULT_ICON[result]} size={16} /> {t(`result.${RESULT_KEY[result]}`)}
    </span>
  );
}

export function TrustChip({ label }) {
  const { t } = useI18n();
  const key = label === 'uncertain' ? 'trust.unverified' : `trust.${label}`;
  const icon = label === 'confirmed' ? 'shield' : label === 'guidance' ? 'doc' : 'warn';
  return (
    <span className={`chip chip-${label}`}>
      <Icon name={icon} size={16} /> {t(key)}
    </span>
  );
}

/** Who checked a published scheme (docs/REVIEW.md §5). AI-only reviews say so plainly. */
export function CheckedChip({ scheme }) {
  const { t, formatDate } = useI18n();
  const v = scheme.verification;
  if (!v || scheme.status !== 'published') return null;
  const ai = v.method === 'ai-dual-review';
  return (
    <span className={`chip ${ai ? 'chip-ai' : 'chip-confirmed'}`}>
      <Icon name={ai ? 'doc' : 'shield'} size={16} /> {t(ai ? 'trust.ai_checked' : 'source.verified', { date: formatDate(v.date) })}
    </span>
  );
}

export function Warn({ children }) {
  return (
    <p className="warn">
      <Icon name="warn" size={18} /> <span>{children}</span>
    </p>
  );
}

export const MARK_ICON = { pass: 'check', fail: 'dash', unknown: 'question' };

export function Mark({ outcome }) {
  return (
    <span className={`mark mark-${outcome}`} aria-hidden="true">
      <Icon name={MARK_ICON[outcome]} size={14} />
    </span>
  );
}
