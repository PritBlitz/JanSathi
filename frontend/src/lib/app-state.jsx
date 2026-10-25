// App state: the rule bundle, the household (family members and their answers), the checklist
// and notices. Everything stays on the device; the rules engine (rules.js) decides every result.
//
// Family mode (brief §6.1): fields with scope "household" in fields.json (state, income, ration
// card, …) are asked once and shared by every member; "member" fields (age, gender, disability, …)
// are answered per person.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { match } from './rules.js';
import { store } from './i18n.js';
import { loadStateSchemes } from './data.js';

export const KEYS = { profile: 'jansathi.profile', checklist: 'jansathi.checklist', motion: 'jansathi.motion', theme: 'jansathi.theme' };
const today = () => new Date().toISOString().slice(0, 10);
const newId = () => Math.random().toString(36).slice(2, 10);
const emptyMember = (name = null) => ({ id: newId(), name, profile: {}, skipped: [], history: [] });

function readJson(key) {
  try {
    const v = JSON.parse(store.get(key) || 'null');
    return v && typeof v === 'object' ? v : null;
  } catch {
    return null; // corrupt storage: start fresh
  }
}

function loadSaved() {
  const p = readJson(KEYS.profile) || {};
  const c = readJson(KEYS.checklist) || {};
  let members = Array.isArray(p.members) ? p.members.filter((m) => m && m.id) : [];
  // Version 1 stored one profile; it becomes the first member ("Me").
  if (!members.length) members = [{ ...emptyMember(), profile: p.profile || {}, skipped: p.skipped || [], history: p.history || [] }];
  const active = members.some((m) => m.id === p.active) ? p.active : members[0].id;
  return { members, active, checklist: { schemes: c.schemes || [], have: c.have || {} } };
}

const AppContext = createContext(null);

export function AppProvider({ data, children }) {
  const { bundle, preview, source } = data;
  const [saved, setSaved] = useState(loadSaved);
  const [notice, setNotice] = useState(null);
  const noticeTimer = useRef();
  const [voiceMode, setVoiceMode] = useState(false);
  const [stateSchemes, setStateSchemes] = useState({});

  const persist = useCallback((next) => {
    setSaved(next);
    store.set(KEYS.profile, JSON.stringify({ version: 2, members: next.members, active: next.active }));
    store.set(KEYS.checklist, JSON.stringify(next.checklist));
  }, []);

  const showNotice = useCallback((msg, ms = 6000) => {
    setNotice(msg);
    clearTimeout(noticeTimer.current);
    noticeTimer.current = setTimeout(() => setNotice(null), ms);
  }, []);

  // A state's own schemes are a separate file, loaded once the household's state is known.
  const stateCode = saved.members[0]?.profile.state;
  useEffect(() => {
    if (!stateCode || !bundle.states?.[stateCode] || stateSchemes[stateCode]) return;
    loadStateSchemes(stateCode, preview)
      .then((schemes) => setStateSchemes((s) => ({ ...s, [stateCode]: schemes })))
      .catch(() => {});
  }, [stateCode, bundle, preview, stateSchemes]);

  const value = useMemo(() => {
    const { members, active, checklist } = saved;
    const fields = bundle.fields.fields;
    const all = { ...bundle, schemes: [...bundle.schemes, ...(stateSchemes[stateCode] || [])] };
    const member = members.find((m) => m.id === active) || members[0];
    const { profile, skipped, history } = member;
    const resultFor = (m) => match(all, m.profile, { skipped: m.skipped, as_of: today() });
    const result = resultFor(member);
    const schemeBy = (slug) => all.schemes.find((s) => s.slug === slug);
    const isHousehold = (field) => fields[field]?.scope === 'household';

    /** Applies a change to the active member, and to everyone for a household field. */
    function update(field, fn, historyFn) {
      persist({
        ...saved,
        members: members.map((m) => {
          if (m.id !== member.id && !isHousehold(field)) return m;
          const next = fn(m);
          return m.id === member.id ? { ...next, history: historyFn(m.history) } : next;
        }),
      });
    }

    /** Records an answer. value null/undefined, or an option marked unknown, means "don't know". */
    function answer(field, value, { exact = false } = {}) {
      const def = fields[field];
      const entry = { field, prev: profile[field], wasSkipped: skipped.includes(field) };
      let known = true;
      let stored = value;
      if (value === null || value === undefined) known = false;
      else if (def.type === 'band' && !exact) {
        const opt = def.options.find((o) => o.value === value);
        if (opt.unknown) known = false;
        else stored = { ...opt.band };
      } else if (def.type === 'enum' && def.options.find((o) => o.value === value)?.unknown) known = false;
      update(
        field,
        (m) => {
          const p = { ...m.profile };
          let s = m.skipped.filter((f) => f !== field);
          if (known) p[field] = stored;
          else {
            delete p[field];
            s = [...s, field];
          }
          return { ...m, profile: p, skipped: s };
        },
        (h) => [...h, entry],
      );
    }

    function skip(field) {
      // A straddling band stays as the user's range; the engine just stops asking for it.
      update(
        field,
        (m) => ({ ...m, skipped: m.skipped.includes(field) ? m.skipped : [...m.skipped, field] }),
        (h) => [...h, { field, prev: profile[field], wasSkipped: false }],
      );
    }

    function back() {
      const last = history[history.length - 1];
      if (!last) return;
      update(
        last.field,
        (m) => {
          const p = { ...m.profile };
          let s = m.skipped.filter((f) => f !== last.field);
          if (last.wasSkipped) s = [...s, last.field];
          if (last.prev === undefined) delete p[last.field];
          else p[last.field] = last.prev;
          return { ...m, profile: p, skipped: s };
        },
        (h) => h.slice(0, -1),
      );
    }

    function addMember(name) {
      // A new member starts with the household's shared answers.
      const shared = Object.fromEntries(Object.entries(members[0].profile).filter(([f]) => isHousehold(f)));
      const m = { ...emptyMember(name || null), profile: shared, skipped: members[0].skipped.filter(isHousehold) };
      persist({ ...saved, members: [...members, m], active: m.id });
      return m.id;
    }

    return {
      bundle: all,
      preview,
      source,
      fields,
      members,
      member,
      profile,
      skipped,
      history,
      checklist,
      result,
      resultFor,
      schemeBy,
      answered: Object.keys(profile).length > 0,
      voiceMode,
      setVoiceMode,
      notice,
      showNotice,
      answer,
      skip,
      back,
      addMember,
      setActive: (id) => persist({ ...saved, active: id }),
      removeMember(id) {
        if (members.length < 2) return;
        const rest = members.filter((m) => m.id !== id);
        persist({ ...saved, members: rest, active: active === id ? rest[0].id : active });
      },
      restart: () => persist({ ...saved, members: members.map((m) => (m.id === member.id ? { ...m, profile: {}, skipped: [], history: [] } : m)) }),
      addToChecklist: (slug) =>
        persist({ ...saved, checklist: { ...checklist, schemes: checklist.schemes.includes(slug) ? checklist.schemes : [...checklist.schemes, slug] } }),
      setHave: (code, have) => persist({ ...saved, checklist: { ...checklist, have: { ...checklist.have, [code]: have } } }),
      clearChecklist: () => persist({ ...saved, checklist: { schemes: [], have: {} } }),
      deleteAll() {
        for (const k of Object.values(KEYS)) store.remove(k);
        store.remove('jansathi.lang');
        const m = emptyMember();
        setSaved({ members: [m], active: m.id, checklist: { schemes: [], have: {} } });
      },
    };
  }, [saved, bundle, preview, source, persist, voiceMode, notice, showNotice, stateSchemes, stateCode]);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export const useApp = () => useContext(AppContext);
