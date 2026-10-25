// JanSathi rules engine (JS). Implements docs/RULES_ENGINE.md §9 exactly; the
// Java engine in api/ must give identical results on the fixtures in rules/.
// No DOM access: this module runs in the browser and in Node (tools/test-rules.mjs).

const T = true;
const F = false;
const U = undefined; // "unknown" in Kleene three-valued logic

export const OPERATORS = ['var', '==', '!=', '<', '<=', '>', '>=', 'in', 'and', 'or', '!'];
const COMPARE = new Set(['<', '<=', '>', '>=']);
const CONFIDENCE_RANK = { uncertain: 0, guidance: 1, confirmed: 2 };
const DAY_MS = 86400000;

export class RuleError extends Error {}

function opOf(node) {
  if (node === null || typeof node !== 'object' || Array.isArray(node)) return null;
  const keys = Object.keys(node);
  if (keys.length !== 1) throw new RuleError(`rule node must have exactly one operator: ${JSON.stringify(node)}`);
  return keys[0];
}

function argsOf(node, op) {
  const a = node[op];
  return Array.isArray(a) ? a : [a];
}

/** Field names a rule reads through {"var": …}, in first-seen order. */
export function referencedFields(logic, out = []) {
  const op = opOf(logic);
  if (op === null) {
    if (Array.isArray(logic)) logic.forEach((x) => referencedFields(x, out));
    return out;
  }
  if (op === 'var') {
    const name = argsOf(logic, op)[0];
    if (typeof name !== 'string') throw new RuleError('var takes a field name');
    if (!out.includes(name)) out.push(name);
    return out;
  }
  argsOf(logic, op).forEach((x) => referencedFields(x, out));
  return out;
}

/**
 * Checks that a rule uses only the §9.2 operators, only known fields, and only
 * comparison operators (<, <=, >, >=) on band fields, so corner evaluation is sound.
 * Returns a list of error strings (empty when valid).
 */
export function validateLogic(logic, fields) {
  const errors = [];
  const walk = (node, parentOp) => {
    const op = opOf(node);
    if (op === null) {
      if (Array.isArray(node)) node.forEach((x) => walk(x, parentOp));
      return;
    }
    if (!OPERATORS.includes(op)) {
      errors.push(`operator not allowed: ${op}`);
      return;
    }
    const args = argsOf(node, op);
    if (op === 'var') {
      const name = args[0];
      const def = fields[name];
      if (!def) errors.push(`unknown field: ${name}`);
      else if (def.type === 'band' && !COMPARE.has(parentOp)) {
        errors.push(`band field ${name} may only be used with <, <=, >, >=`);
      }
      return;
    }
    if (['==', '!=', '<', '<=', '>', '>=', 'in'].includes(op) && args.length !== 2) {
      errors.push(`${op} takes exactly 2 operands`);
    }
    if (op === 'in' && !Array.isArray(args[1])) errors.push('in needs a literal array as its second operand');
    if (op === '!' && args.length !== 1) errors.push('! takes exactly 1 operand');
    args.forEach((x) => walk(x, op));
  };
  try {
    walk(logic, null);
  } catch (e) {
    errors.push(e.message);
  }
  return errors;
}

/** Evaluates logic against a flat object of plain values. Returns true, false or undefined (U). */
export function evalLogic(node, values) {
  const op = opOf(node);
  if (op === null) return node; // literal (number, string, bool, array)
  const args = argsOf(node, op);
  switch (op) {
    case 'var': {
      const name = args[0];
      return Object.prototype.hasOwnProperty.call(values, name) ? values[name] : U;
    }
    case '==':
    case '!=':
    case '<':
    case '<=':
    case '>':
    case '>=': {
      const a = evalLogic(args[0], values);
      const b = evalLogic(args[1], values);
      if (a === U || b === U) return U;
      if (op === '==') return a === b;
      if (op === '!=') return a !== b;
      if (typeof a !== 'number' || typeof b !== 'number') return F;
      if (op === '<') return a < b;
      if (op === '<=') return a <= b;
      if (op === '>') return a > b;
      return a >= b;
    }
    case 'in': {
      const x = evalLogic(args[0], values);
      if (x === U) return U;
      const arr = args[1];
      return Array.isArray(arr) ? arr.includes(x) : F;
    }
    case '!': {
      const x = evalLogic(args[0], values);
      return x === U ? U : !x;
    }
    case 'and': {
      let unknown = false;
      for (const a of args) {
        const v = evalLogic(a, values);
        if (v === F) return F;
        if (v === U) unknown = true;
      }
      return unknown ? U : T;
    }
    case 'or': {
      let unknown = false;
      for (const a of args) {
        const v = evalLogic(a, values);
        if (v === T) return T;
        if (v === U) unknown = true;
      }
      return unknown ? U : F;
    }
    default:
      throw new RuleError(`operator not allowed: ${op}`);
  }
}

/** Normalises a band profile value to {min, max}; an exact number n becomes {n, n}. */
export function toBand(v) {
  if (typeof v === 'number') return { min: v, max: v };
  if (v && typeof v === 'object' && typeof v.min === 'number' && typeof v.max === 'number') return v;
  return null;
}

function fieldOrder(fields) {
  return Object.keys(fields);
}

function sortByFieldOrder(names, order) {
  return [...new Set(names)].sort((a, b) => {
    const ia = order.indexOf(a);
    const ib = order.indexOf(b);
    return (ia < 0 ? Infinity : ia) - (ib < 0 ? Infinity : ib) || (a < b ? -1 : a > b ? 1 : 0);
  });
}

/**
 * §9.3: evaluates one rule against a profile.
 * Returns { outcome: 'pass'|'fail'|'unknown', missing_fields: [...] }.
 */
export function evaluateRule(rule, profile, fields) {
  const order = fieldOrder(fields);
  const refs = referencedFields(rule.logic);
  const base = {};
  const absent = [];
  const straddling = []; // band fields with min != max
  for (const name of refs) {
    if (!Object.prototype.hasOwnProperty.call(profile, name)) {
      absent.push(name);
      continue;
    }
    const def = fields[name];
    if (def && def.type === 'band') {
      const band = toBand(profile[name]);
      if (!band) {
        absent.push(name);
        continue;
      }
      if (band.min === band.max) base[name] = band.min;
      else straddling.push(name);
    } else {
      base[name] = profile[name];
    }
  }
  const B = sortByFieldOrder(straddling, order);

  // Corners: cartesian product of {min, max} over B, in fields.json order.
  // Corner k picks max for B[i] when bit (B.length - 1 - i) of k is set.
  const n = B.length;
  const cornerValue = (k) => {
    const values = { ...base };
    B.forEach((name, i) => {
      const band = toBand(profile[name]);
      values[name] = (k >> (n - 1 - i)) & 1 ? band.max : band.min;
    });
    return evalLogic(rule.logic, values);
  };
  const results = [];
  for (let k = 0; k < 1 << n; k++) results.push(cornerValue(k));

  let outcome;
  if (results.every((r) => r === T)) outcome = 'pass';
  else if (results.every((r) => r === F)) outcome = 'fail';
  else outcome = 'unknown';

  let missing = [];
  if (outcome === 'unknown') {
    missing = [...absent];
    B.forEach((name, i) => {
      const bit = 1 << (n - 1 - i);
      for (let k = 0; k < 1 << n; k++) {
        if (results[k] !== results[k ^ bit]) {
          missing.push(name);
          break;
        }
      }
    });
    missing = sortByFieldOrder(missing, order);
  }
  return { outcome, missing_fields: missing };
}

function daysBetween(fromIso, toIso) {
  return Math.floor((Date.parse(toIso) - Date.parse(fromIso)) / DAY_MS);
}

/** §9.4–9.5: evaluates one scheme. Returns the per-scheme result object of POST /api/v1/match. */
export function evaluateScheme(scheme, profile, fields, { as_of } = {}) {
  const sources = new Map((scheme.sources || []).map((s) => [s.id, s]));
  const rules = (scheme.rules || []).map((rule) => {
    const { outcome, missing_fields } = evaluateRule(rule, profile, fields);
    return {
      code: rule.code,
      outcome,
      hard: !!rule.hard,
      confidence: rule.confidence,
      source: rule.source,
      missing_fields,
    };
  });
  const hard = rules.filter((r) => r.hard);

  let result;
  if (hard.some((r) => r.outcome === 'fail' && r.confidence !== 'uncertain')) result = 'NOT_ELIGIBLE';
  else if (hard.some((r) => r.outcome === 'unknown' || (r.outcome === 'fail' && r.confidence === 'uncertain'))) {
    result = 'NEED_MORE_INFO';
  } else result = 'LIKELY_ELIGIBLE';

  const missing = [];
  for (const r of hard) {
    if (r.outcome !== 'unknown') continue;
    for (const f of r.missing_fields) if (!missing.includes(f)) missing.push(f);
  }

  let stale = scheme.status !== 'published';
  const reverify = scheme.reverify_days ?? 180;
  for (const r of hard) {
    const src = sources.get(r.source);
    if (!src || !src.verified_at) {
      stale = true;
      continue;
    }
    if (as_of && daysBetween(src.verified_at, as_of) > reverify) stale = true;
  }

  let label = 'confirmed';
  for (const r of hard) {
    if ((CONFIDENCE_RANK[r.confidence] ?? 0) < CONFIDENCE_RANK[label]) label = r.confidence;
  }
  if (stale) label = 'uncertain';

  return {
    scheme: scheme.slug,
    result,
    label,
    stale,
    missing_fields: missing,
    rules,
  };
}

/** §9.6: the field that would resolve the most NEED_MORE_INFO schemes, or null. */
export function nextQuestion(results, fields, skipped = []) {
  const order = fieldOrder(fields);
  const counts = new Map();
  for (const r of results) {
    if (r.result !== 'NEED_MORE_INFO') continue;
    for (const f of r.missing_fields) {
      if (skipped.includes(f)) continue;
      counts.set(f, (counts.get(f) || 0) + 1);
    }
  }
  let best = null;
  let bestCount = 0;
  for (const f of sortByFieldOrder([...counts.keys()], order)) {
    if (counts.get(f) > bestCount) {
      best = f;
      bestCount = counts.get(f);
    }
  }
  return best;
}

/**
 * Matches a profile against every scheme in the bundle (or the slugs in opts.schemes).
 * Returns the response shape of POST /api/v1/match.
 */
export function match(bundle, profile, { skipped = [], as_of, schemes } = {}) {
  const fields = bundle.fields.fields;
  const list = schemes ? bundle.schemes.filter((s) => schemes.includes(s.slug)) : bundle.schemes;
  const date = as_of || new Date().toISOString().slice(0, 10);
  const results = list.map((s) => evaluateScheme(s, profile, fields, { as_of: date }));
  return {
    ruleset_version: bundle.version,
    next_question: nextQuestion(results, fields, skipped),
    results,
  };
}
