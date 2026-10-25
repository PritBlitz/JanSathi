// Copy lint: blocks phrases that promise eligibility or approval (CLAUDE.md principle 1).
// Checks every string in web/locales/*.json and content/**/*.json, and prompts in prompts/ if present.
// Usage: node tools/lint-copy.mjs   (exit 1 on any hit)
//
// The non-English lists were machine-authored and must be extended by native-speaker reviewers
// (docs/I18N_AND_VOICE.md §6). ALLOW strips official names before matching, e.g. the
// "Viksit Bharat – Guarantee for Rozgar and Ajeevika Mission (Gramin) Act" and its job card.
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

export const BANNED = {
  en: [/\byou(?:'re| are) (?:definitely |certainly )?eligible\b/i, /\byou will (?:get|receive)\b/i, /\bguaranteed\b/i, /\byou qualify\b/i, /\bapproved for you\b/i],
  hi: [/आप पात्र हैं/, /आप योग्य हैं/, /आपको (?:ज़रूर |जरूर )?मिलेगा/, /आपको (?:ज़रूर |जरूर )?मिलेगी/, /गारंटी/],
  mr: [/तुम्ही पात्र आहात/, /तुम्हाला मिळेल/, /हमी/, /गॅरंटी/],
  bn: [/আপনি যোগ্য/, /আপনি পাবেন/, /নিশ্চিত/, /গ্যারান্টি/],
  ta: [/நீங்கள் தகுதியானவர்/, /உங்களுக்கு கிடைக்கும்/, /உத்தரவாதம்/],
  te: [/మీరు అర్హులు/, /మీకు లభిస్తుంది/, /హామీ/, /గ్యారంటీ/],
  gu: [/તમે પાત્ર છો/, /તમને મળશે/, /ગેરંટી/, /ખાતરી/],
  kn: [/ನೀವು ಅರ್ಹರು/, /ನಿಮಗೆ ಸಿಗುತ್ತದೆ/, /ಖಾತರಿ/, /ಗ್ಯಾರಂಟಿ/],
  ml: [/നിങ്ങൾ അർഹ(?:നാണ്|യാണ്|രാണ്)/, /നിങ്ങൾക്ക് ലഭിക്കും/, /ഉറപ്പ്/, /ഗ്യാരണ്ടി/],
  pa: [/ਤੁਸੀਂ ਯੋਗ ਹੋ/, /ਤੁਹਾਨੂੰ ਮਿਲੇਗਾ/, /ਗਾਰੰਟੀ/],
};

const ALLOW = [
  /Guarantee for Rozgar(?: and Ajeevika Mission)?/gi,
  /Rozgar Guarantee Card/gi,
  /Employment Guarantee(?: Act| Scheme)?/gi,
  // ज़ can be precomposed (U+095B) or ज + nukta (U+093C).
  /रो(?:ज़?|ज़)गार गारंटी/g,
  /गारंटी फ़?ॉर रो(?:ज़?|ज़)गार/g,
];

const LOCALE_OF = (file) => file.match(/locales[\\/](\w+)\.json$/)?.[1];

function strings(value, path, out) {
  if (typeof value === 'string') out.push([path, value]);
  else if (Array.isArray(value)) value.forEach((v, i) => strings(v, `${path}[${i}]`, out));
  else if (value && typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) if (!k.startsWith('$')) strings(v, path ? `${path}.${k}` : k, out);
  }
  return out;
}

function walk(dir, ext, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, ext, out);
    else if (ext.some((e) => name.endsWith(e))) out.push(p);
  }
  return out;
}

export function lintText(text, langs = Object.keys(BANNED)) {
  let t = text;
  for (const a of ALLOW) t = t.replace(a, ' ');
  const hits = [];
  for (const l of langs) for (const re of BANNED[l]) if (re.test(t)) hits.push(re.source);
  return hits;
}

const files = [
  ...walk(join(root, 'web/locales'), ['.json']),
  ...walk(join(root, 'content'), ['.json']),
  ...walk(join(root, 'prompts'), ['.txt', '.md', '.json']),
];
const problems = [];
for (const file of files) {
  const rel = relative(root, file);
  if (rel.endsWith('review-status.json')) continue;
  const items = file.endsWith('.json') ? strings(JSON.parse(readFileSync(file, 'utf8')), '', []) : [['', readFileSync(file, 'utf8')]];
  const loc = LOCALE_OF(file);
  // Locale files: English plus their own language. Content and prompts: every language.
  const langs = loc ? [...new Set(['en', loc])].filter((l) => BANNED[l]) : Object.keys(BANNED);
  for (const [path, text] of items) {
    for (const hit of lintText(text, langs)) problems.push(`${rel} ${path}: banned phrase /${hit}/ in "${text.slice(0, 90)}"`);
  }
}

if (problems.length) {
  console.error(problems.join('\n'));
  console.error(`\n${problems.length} banned phrase(s). Use "may be eligible", "likely relevant" or "need more information".`);
  process.exit(1);
}
console.log(`copy lint OK: ${files.length} files`);
