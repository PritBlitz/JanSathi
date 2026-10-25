// Questionnaire (DESIGN §7.1): one question per screen, large icon tiles, speak/mic, skip.
// Core fields first, then the engine's next_question (RULES_ENGINE §9.6).
import { useEffect, useRef, useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useApp } from '../lib/app-state.jsx';
import { useSay } from '../lib/use-speech.js';
import { canListen, listen } from '../lib/speech.js';
import { toBand } from '../lib/rules.js';
import { matchOption, normalise } from '../lib/text.js';
import { memberName } from './Family.jsx';

function currentQuestion({ bundle, profile, skipped, result, fields }) {
  const core = bundle.fields.core.find((f) => !(f in profile) && !skipped.includes(f));
  if (core) return { field: core, exact: false };
  const f = result.next_question;
  if (!f) return null;
  return { field: f, exact: f in profile && fields[f].type === 'band' };
}

function remainingEstimate({ bundle, profile, skipped, result }) {
  const set = new Set();
  for (const r of result.results) if (r.result === 'NEED_MORE_INFO') r.missing_fields.forEach((f) => !skipped.includes(f) && set.add(f));
  bundle.fields.core.forEach((f) => !(f in profile) && !skipped.includes(f) && set.add(f));
  return set.size;
}

export function Question() {
  const { t, speech, formatNumber } = useI18n();
  const app = useApp();
  const { fields, profile, history, voiceMode, setVoiceMode, answer, skip, back, members, member } = app;
  const answeringFor = members.length > 1 ? t('family.answering', { name: memberName(member, members.indexOf(member), t) }) : null;
  const say = useSay();
  const titleRef = useRef(null);
  const [status, setStatus] = useState('');
  const [exact, setExact] = useState('');
  const q = currentQuestion(app);
  const answered = history.length;

  const def = q && fields[q.field];
  const opts = !q || q.exact
    ? []
    : def.type === 'bool'
      ? [
          { value: true, label: t('opt.bool.yes'), icon: '✅' },
          { value: false, label: t('opt.bool.no'), icon: '❌' },
          { value: null, label: t('opt.bool.dont_know'), icon: '🤷' },
        ]
      : def.options.map((o) => ({ value: o.value, label: t(`opt.${q.field}.${o.value}`), icon: o.icon }));
  const title = q ? (q.exact ? t(`q.exact.${q.field}`) : t(`q.${q.field}`)) : '';
  const speechText = `${title} ${opts.map((o) => o.label).join('. ')}`;

  useEffect(() => {
    setStatus('');
    setExact('');
    if (answered) titleRef.current?.focus();
    if (q && voiceMode) say(speechText);
    // Only when the question changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q?.field, q?.exact]);

  if (!q) {
    return (
      <div className="page q-page">
        <div className="q-wrap">
          <p className="kicker">{t('q.kicker', { n: formatNumber(answered) })}</p>
          <h1 className="display-title">{t('q.done_title')}</h1>
          <p className="lead">{t('q.done_body')}</p>
          <div className="actions">
            <a className="btn btn-primary btn-hero" href="#/results">
              {t('q.see_results')} <Icon name="arrow" size={20} />
            </a>
            {answered > 0 && (
              <button className="btn btn-outline" type="button" onClick={back}>
                {t('q.back')}
              </button>
            )}
          </div>
        </div>
      </div>
    );
  }

  const pct = Math.round((answered / (answered + remainingEstimate(app) || 1)) * 100);
  const helpKey = `help.${q.field}`;
  const pick = (o) => {
    if (voiceMode) say(o.label);
    answer(q.field, o.value);
  };

  async function mic() {
    setVoiceMode(true);
    setStatus(t('q.listening'));
    try {
      const heard = normalise(await listen(speech, (text) => setStatus(text)));
      let idx = matchOption(heard, opts.map((o) => o.label));
      const num = heard.match(/\d+/);
      if (idx < 0 && num && def.type === 'band') {
        const n = Number(num[0]);
        idx = def.options.findIndex((o) => o.band && n >= o.band.min && n <= o.band.max);
      }
      if (idx < 0) setStatus(t('q.no_match'));
      else answer(q.field, opts[idx].value);
    } catch (e) {
      setStatus(t(e.message === 'denied' ? 'ask.denied' : e.message === 'unsupported' ? 'ask.unsupported' : 'q.no_match'));
    }
  }

  const band = q.exact ? toBand(profile[q.field]) : null;
  return (
    <div className="page q-page">
      <div className="q-wrap">
        <div className="q-top">
          <span className="q-count" aria-hidden="true">
            {formatNumber(answered + 1).padStart(2, formatNumber(0))}
          </span>
          <div className="q-top-text">
            <p className="kicker">{t('q.kicker', { n: formatNumber(answered + 1) })}</p>
            <div
              className="progress"
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={pct}
              aria-label={t('q.kicker', { n: formatNumber(answered + 1) })}
            >
              <span style={{ width: `${pct}%` }} />
            </div>
          </div>
          <a className="btn btn-ghost btn-sm" href="#/results">
            {t('q.see_results')}
          </a>
        </div>

        {answeringFor && <p className="member-tag">{answeringFor}</p>}
        <h1 className="q-title" ref={titleRef} tabIndex={-1}>
          {title}
        </h1>
        {q.exact ? <p className="q-help">{t('q.exact_why')}</p> : t(helpKey) !== helpKey && <p className="q-help">{t(helpKey)}</p>}

        <div className="q-tools">
          <button
            className="btn btn-outline btn-sm"
            type="button"
            onClick={() => {
              setVoiceMode(true);
              say(speechText);
            }}
          >
            <Icon name="speaker" size={18} /> {t('q.speak')}
          </button>
          {!q.exact && canListen() && (
            <button className="btn btn-outline btn-sm" type="button" onClick={mic}>
              <Icon name="mic" size={18} /> {t('q.mic')}
            </button>
          )}
        </div>
        <p className="small q-status" role="status" aria-live="polite">
          {status}
        </p>

        {q.exact ? (
          <form
            className="exact"
            onSubmit={(e) => {
              e.preventDefault();
              const n = Number(exact);
              if (exact === '' || !Number.isFinite(n)) setStatus(t('q.exact_invalid'));
              else answer(q.field, n, { exact: true });
            }}
          >
            <label className="visually-hidden" htmlFor="exact-input">
              {title}
            </label>
            <input
              id="exact-input"
              type="number"
              inputMode="numeric"
              min={band.min}
              max={band.max}
              required
              value={exact}
              onChange={(e) => setExact(e.target.value)}
              placeholder={`${formatNumber(band.min)} – ${formatNumber(band.max)}`}
            />
            <button className="btn btn-primary" type="submit">
              {t('q.exact_submit')}
            </button>
          </form>
        ) : (
          <ul className="options">
            {opts.map((o, i) => (
              <li key={String(o.value)} style={{ '--i': i }}>
                <button className="option" type="button" onClick={() => pick(o)}>
                  <span className="option-icon" aria-hidden="true">
                    {o.icon}
                  </span>
                  <span className="option-label">{o.label}</span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <div className="q-foot">
          <button className="btn btn-outline" type="button" onClick={back} disabled={!answered}>
            <Icon name="arrowLeft" size={18} /> {t('q.back')}
          </button>
          <button className="btn btn-ghost" type="button" onClick={() => skip(q.field)}>
            {t('q.skip')}
          </button>
        </div>
        <p className="privacy-note">{t('q.privacy_note')}</p>
      </div>
    </div>
  );
}
