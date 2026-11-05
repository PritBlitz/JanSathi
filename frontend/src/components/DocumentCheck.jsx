// Optional photo check on a checklist item (brief §6.4). The photo is read on this device and is
// not kept. The result says only whether it is readable and looks like the right kind of document:
// it never says a document is genuine, and the text read from it is never shown.
import { useRef, useState } from 'react';
import { Icon } from './Icon.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useSay } from '../lib/use-speech.js';
import { canCheck } from '../lib/ocr-check.js';

export function DocumentCheck({ code, name }) {
  const { t } = useI18n();
  const say = useSay();
  const input = useRef(null);
  const [state, setState] = useState(null); // null | 'working' | a verdict | 'failed'
  if (!canCheck(code)) return null;

  async function onPick(e) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setState('working');
    try {
      const { checkDocumentPhoto } = await import('../lib/ocr.js');
      const verdict = await checkDocumentPhoto(file, code);
      setState(verdict);
      say(`${t(`ocr.${verdict}`)} ${t('ocr.not_proof')}`);
    } catch {
      setState('failed');
    }
  }

  return (
    <div className="doc-check">
      <input
        ref={input}
        className="visually-hidden"
        type="file"
        accept="image/*"
        capture="environment"
        tabIndex={-1}
        aria-hidden="true"
        data-doc-check={code}
        onChange={onPick}
      />
      <button className="btn btn-ghost btn-sm" type="button" disabled={state === 'working'} aria-label={t('ocr.button_for', { document: name })} onClick={() => input.current.click()}>
        <Icon name="search" size={16} /> {t('ocr.button')}
      </button>
      <p className="small muted">{t('ocr.privacy')}</p>
      <div role="status" aria-live="polite">
        {state && (
          <p className={`doc-check-result is-${state}`} data-verdict={state}>
            {t(`ocr.${state}`)} {state !== 'working' && state !== 'failed' && state !== 'too_large' && t('ocr.not_proof')}
          </p>
        )}
      </div>
    </div>
  );
}
