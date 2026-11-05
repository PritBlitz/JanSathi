// Saathi, the AI guide: a conversation in the person's language, with tap-to-answer question cards,
// "saved" chips with undo, and scheme cards and official passages for everything it mentions.
// Without a configured model it still steps through the questions.
import { useEffect, useRef, useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { PageHead, SchemeCard, EmptyState } from '../components/SchemeParts.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSession } from '../lib/session.jsx';
import { useSay } from '../lib/use-speech.js';
import { canListen, listen } from '../lib/speech.js';
import { hostOf } from '../lib/scheme.js';

const FALLBACK_KEY = { no_model: 'guide.no_model', unavailable: 'guide.unavailable', unsafe: 'guide.unsafe' };

export function Guide() {
  const { t, lang, speech, formatNumber } = useI18n();
  const session = useSession();
  const { schemeBy, result, replaceHousehold, back, voiceMode, setVoiceMode } = useApp();
  const say = useSay();
  const [lines, setLines] = useState([]);
  const [question, setQuestion] = useState(null);
  const [text, setText] = useState('');
  const [exact, setExact] = useState('');
  const [busy, setBusy] = useState(false);
  const [listening, setListening] = useState(false);
  const [error, setError] = useState('');
  const endRef = useRef(null);

  // Load the conversation, then ask for the current question (no model call without a message).
  useEffect(() => {
    if (!session.signedIn) return;
    session
      .call('/me/conversation')
      .then((history) => setLines(history.map((l) => ({ role: l.role, text: l.text }))))
      .catch(() => {});
    send({});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.signedIn]);

  // Block body: scrollIntoView may return a Promise in newer browsers, and an effect must not.
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' });
  }, [lines.length, busy]);

  if (!session.available) {
    return (
      <div className="page">
        <PageHead kicker={t('nav.guide')} title={t('guide.title')} />
        <EmptyState title={t('auth.unavailable')} />
      </div>
    );
  }
  if (!session.signedIn) {
    return (
      <div className="page">
        <PageHead kicker={t('nav.guide')} title={t('guide.title')} lead={t('guide.lead')} />
        <a className="btn btn-primary btn-hero" href="#/login?next=%23%2Fguide">
          {t('guide.sign_in')} <Icon name="arrow" size={20} />
        </a>
      </div>
    );
  }

  async function send(body, userLine) {
    setBusy(true);
    setError('');
    if (userLine) setLines((ls) => [...ls, { role: 'user', text: userLine }]);
    try {
      const r = await session.call('/guide/messages', { method: 'POST', body: { ...body, lang } });
      if (r.household) replaceHousehold(r.household);
      setQuestion(r.question || null);
      setExact('');
      const replyText = r.reply || (r.fallback && userLine ? t(FALLBACK_KEY[r.fallback]) : null);
      if (replyText || r.recorded?.length || r.schemes?.length || r.help_requested) {
        setLines((ls) => [
          ...ls,
          { role: 'guide', text: replyText, recorded: r.recorded, schemes: r.schemes, citations: r.citations, help: r.help_requested, ai: !!r.reply },
        ]);
        if (voiceMode && replyText) say(replyText);
      }
    } catch (e) {
      setError(t(e.status === 429 ? 'guide.slow_down' : 'common.error'));
    } finally {
      setBusy(false);
    }
  }

  const submit = (e) => {
    e.preventDefault();
    const msg = text.trim();
    if (!msg || busy) return;
    setText('');
    send({ text: msg }, msg);
  };

  async function mic() {
    setVoiceMode(true);
    setListening(true);
    try {
      const heard = await listen(speech, (partial) => setText(partial));
      if (heard) {
        setText('');
        send({ text: heard }, heard);
      }
    } catch (e) {
      setError(t(e.message === 'denied' ? 'ask.denied' : 'ask.unsupported'));
    } finally {
      setListening(false);
    }
  }

  const tap = (o) => send({ answer: { field: question.field, value: o.value } }, o.label);

  return (
    <div className="page guide-page">
      <PageHead kicker={t('nav.guide')} title={t('guide.title')} lead={t('guide.lead')} />
      <p className="small muted guide-ai-note">
        <Icon name="warn" size={16} /> {t('guide.ai_note')}
      </p>

      <ol className="chat" aria-live="polite">
        {lines.map((l, i) => (
          <li key={i} className={`bubble bubble-${l.role}`}>
            <span className="visually-hidden">{t(l.role === 'user' ? 'guide.you' : 'guide.saathi')}: </span>
            {l.text && <p>{l.text}</p>}
            {l.recorded?.map((r) => (
              <span className="saved-chip" key={r.field}>
                <Icon name="check" size={14} /> {t('guide.saved', { answer: r.label })}
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => {
                    back();
                    setTimeout(() => send({}), 900);
                  }}
                >
                  {t('guide.undo')}
                </button>
              </span>
            ))}
            {l.help && <p className="small">{t('guide.help_requested')}</p>}
            {l.role === 'guide' && l.text && (
              <button type="button" className="btn btn-ghost btn-sm bubble-read" onClick={() => say(l.text)}>
                <Icon name="speaker" size={16} /> {t('result.read')}
              </button>
            )}
            {l.citations?.length > 0 && (
              <details className="bubble-sources">
                <summary>{t('guide.sources')}</summary>
                <ol>
                  {l.citations.map((p, j) => (
                    <li key={j}>
                      <span lang={p.text.lang}>{p.text.text}</span>{' '}
                      <a href={p.source.url} rel="noopener noreferrer" target="_blank" lang="en">
                        {p.source.publisher}
                      </a>{' '}
                      <span className="muted">({hostOf(p.source.url)})</span>
                    </li>
                  ))}
                </ol>
              </details>
            )}
            {l.schemes?.length > 0 && (
              <ul className="card-grid bubble-schemes" aria-label={t('guide.schemes')}>
                {l.schemes
                  .map((slug) => schemeBy(slug))
                  .filter(Boolean)
                  .slice(0, 4)
                  .map((s) => (
                    <SchemeCard key={s.slug} scheme={s} result={result.results.find((r) => r.scheme === s.slug)} />
                  ))}
              </ul>
            )}
          </li>
        ))}
        {busy && (
          <li className="bubble bubble-guide bubble-busy" aria-busy="true">
            {t('guide.thinking')}
          </li>
        )}
      </ol>
      <div ref={endRef} />

      {question && !busy && (
        <section className="question-card" aria-labelledby="guide-q">
          <p className="kicker">{t('guide.question')}</p>
          <h2 id="guide-q" className="question-card-title">
            {question.question}
          </h2>
          {question.help && <p className="muted">{question.help}</p>}
          {question.exact ? (
            <form
              className="exact"
              onSubmit={(e) => {
                e.preventDefault();
                const n = Number(exact);
                if (exact !== '' && Number.isFinite(n)) send({ answer: { field: question.field, value: n } }, formatNumber(n));
              }}
            >
              <label className="visually-hidden" htmlFor="guide-exact">
                {question.question}
              </label>
              <input id="guide-exact" type="number" inputMode="numeric" value={exact} onChange={(e) => setExact(e.target.value)} />
              <button className="btn btn-primary" type="submit">
                {t('q.exact_submit')}
              </button>
            </form>
          ) : (
            <ul className="quick-replies">
              {question.options.map((o) => (
                <li key={String(o.value)}>
                  <button type="button" className="filter-chip" onClick={() => tap(o)}>
                    {o.label}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <form className="search-form chat-input" onSubmit={submit}>
        <label className="visually-hidden" htmlFor="guide-input">
          {t('guide.placeholder')}
        </label>
        <input id="guide-input" value={text} placeholder={t('guide.placeholder')} autoComplete="off" maxLength={600} onChange={(e) => setText(e.target.value)} />
        {canListen(speech) && (
          <button className={`icon-btn ${listening ? 'is-listening' : ''}`} type="button" aria-label={t('guide.mic')} onClick={mic}>
            <Icon name="mic" />
          </button>
        )}
        <button className="btn btn-primary" type="submit" disabled={busy || !text.trim()}>
          {t('guide.send')}
        </button>
      </form>
      <p className="small q-status" role="status">
        {error}
      </p>
      <p className="actions">
        <a className="btn btn-outline btn-sm" href="#/account">
          {t('share.title')}
        </a>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={async () => {
            if (!confirm(t('guide.forget_confirm'))) return;
            await session.call('/me/conversation', { method: 'DELETE' }).catch(() => {});
            setLines([]);
          }}
        >
          {t('guide.forget')}
        </button>
      </p>
    </div>
  );
}
