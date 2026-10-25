// Voice ask sheet (DESIGN §7.1): big mic, live transcript, typing fallback, answers below.
// With the API: cited rule passages from POST /api/v1/ask. Without it: keyword search on the device.
// Either way, nothing is answered without an official source.
import { useEffect, useRef, useState } from 'react';
import { Icon } from './Icon.jsx';
import { SchemeCard } from './SchemeParts.jsx';
import { TrustChip, Warn } from './Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { canListen, listen } from '../lib/speech.js';
import { searchSchemes } from '../lib/text.js';
import { askApi, hasApi } from '../lib/data.js';
import { hostOf } from '../lib/scheme.js';

export function AskSheet({ open, onClose }) {
  const { t, lang, speech, pick, formatNumber } = useI18n();
  const { bundle, result } = useApp();
  const dialog = useRef(null);
  const [text, setText] = useState('');
  const [status, setStatus] = useState('');
  const [listening, setListening] = useState(false);
  const [found, setFound] = useState(null);

  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    if (open && !d.open) {
      setFound(null);
      setStatus(canListen() ? '' : t('ask.unsupported'));
      if (d.showModal) d.showModal();
      else d.setAttribute('open', '');
      // Focus on open, not on mount: autoFocus inside the closed dialog forced a layout at startup.
      d.querySelector('.mic-big, #ask-input')?.focus();
    } else if (!open && d.open) {
      if (d.close) d.close();
      else d.removeAttribute('open');
    }
  }, [open, t]);

  async function run(question) {
    if (!question.trim()) return;
    const api = await askApi(question, lang);
    if (api) setFound({ kind: 'api', data: api });
    else {
      const categoryLabel = (c) => t(`cat.${c}`);
      setFound({ kind: 'local', schemes: searchSchemes(bundle.schemes, question, { lang, pick, categoryLabel }) });
    }
  }

  async function mic() {
    setListening(true);
    setStatus(t('ask.listening'));
    try {
      const heard = await listen(speech, (partial) => setText(partial));
      if (heard) {
        setText(heard);
        setStatus(t('ask.heard', { text: heard }));
        run(heard);
      } else setStatus('');
    } catch (e) {
      setStatus(t(e.message === 'denied' ? 'ask.denied' : e.message === 'unsupported' ? 'ask.unsupported' : 'q.no_match'));
    } finally {
      setListening(false);
    }
  }

  return (
    <dialog className="sheet" ref={dialog} aria-labelledby="ask-title" onClose={onClose} onCancel={onClose}>
      <div className="sheet-head">
        <h2 className="sheet-title" id="ask-title">
          {t('ask.title')}
        </h2>
        <button className="icon-btn" type="button" aria-label={t('ask.close')} onClick={onClose}>
          <Icon name="close" />
        </button>
      </div>
      {canListen() && (
        <button className={`mic-big ${listening ? 'listening' : ''}`} type="button" aria-label={t('ask.mic')} onClick={mic}>
          <Icon name="mic" size={40} />
        </button>
      )}
      <p className="ask-status" role="status" aria-live="polite">
        {status}
      </p>
      <form
        className="search-form"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          run(text);
        }}
      >
        <label className="visually-hidden" htmlFor="ask-input">
          {t('search.label')}
        </label>
        <input id="ask-input" type="search" autoComplete="off" value={text} placeholder={t('search.placeholder')} onChange={(e) => setText(e.target.value)} />
        <button className="btn btn-primary" type="submit">
          <Icon name="search" size={18} /> {t('search.button')}
        </button>
      </form>
      <p className="small muted">{hasApi() ? t('ask.sources_note') : t('search.note')}</p>

      {found?.kind === 'local' &&
        (found.schemes.length ? (
          <>
            <p role="status">{t('search.results', { n: formatNumber(found.schemes.length) })}</p>
            <ul className="card-grid">
              {found.schemes.map((s) => (
                <SchemeCard key={s.slug} scheme={s} result={result.results.find((r) => r.scheme === s.slug)} />
              ))}
            </ul>
          </>
        ) : (
          <Warn>{t('search.none')}</Warn>
        ))}

      {found?.kind === 'api' &&
        (found.data.status === 'not_found' ? (
          <Warn>{t('search.none')}</Warn>
        ) : (
          <section className="passages" aria-labelledby="passages-title">
            <div className="passages-head">
              <h3 id="passages-title">{t('ask.sources_title')}</h3>
              <TrustChip label={found.data.label} />
            </div>
            {found.data.status === 'answered' && (
              <div className="answer">
                <p className="answer-text">{found.data.answer}</p>
                {found.data.follow_up && <p className="muted">{found.data.follow_up}</p>}
                <p className="small muted">{t('ask.answer_note')}</p>
              </div>
            )}
            <ol className="passage-list">
              {found.data.passages.map((p) => (
                <li key={p.id} id={`passage-${p.id}`}>
                  <span className="passage-id">[{p.id}]</span>
                  <a className="passage-scheme" href={`#/scheme/${p.scheme}`} onClick={onClose} lang={p.scheme_name.lang}>
                    {p.scheme_name.text}
                  </a>
                  <p lang={p.text.lang}>{p.text.text}</p>
                  <p className="source-meta">
                    <a href={p.source.url} rel="noopener noreferrer" target="_blank" lang="en">
                      {p.source.publisher}
                    </a>{' '}
                    ({hostOf(p.source.url)}){p.locator ? ` · ${t('source.locator', { locator: p.locator })}` : ''}
                  </p>
                  {p.stale && <p className="small warn-text">{t('trust.stale_warning')}</p>}
                </li>
              ))}
            </ol>
          </section>
        ))}
    </dialog>
  );
}
