// Human-readable answers, for the helper portal and the guide's "saved" chips.
import { toBand } from './rules.js';

/** The label of a stored profile value: the option's text, yes/no, or the exact number. */
export function answerLabel(fields, field, value, t, formatNumber) {
  const def = fields[field];
  if (!def || value === undefined) return '';
  if (def.type === 'bool') return t(value ? 'opt.bool.yes' : 'opt.bool.no');
  if (def.type === 'band') {
    const b = toBand(value);
    if (!b) return '';
    const opt = def.options.find((o) => o.band && o.band.min === b.min && o.band.max === b.max);
    return opt ? t(`opt.${field}.${opt.value}`) : formatNumber(b.min);
  }
  return t(`opt.${field}.${value}`);
}
