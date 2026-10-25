// Text matching for search and voice answers. Same normalisation as the API (search/TextMatch.java).

// Keeps combining marks (\p{M}): Indic vowel signs are marks, not letters.
export const normalise = (s) =>
  String(s).toLowerCase().normalize('NFC').replace(/[^\p{L}\p{M}\p{N}\s]/gu, ' ').replace(/\s+/g, ' ').trim();

/** Index of the option whose label words all appear as whole words in what was heard, or -1. */
export function matchOption(heard, labels) {
  const said = new Set(normalise(heard).split(' '));
  let best = -1;
  let bestLen = 0;
  labels.forEach((label, i) => {
    const words = normalise(label).split(' ').filter(Boolean);
    if (words.length && words.every((w) => said.has(w)) && words.length > bestLen) {
      best = i;
      bestLen = words.length;
    }
  });
  return best;
}

/** Schemes whose text (English and the user's language) contains the query words, best first. */
export function searchSchemes(schemes, query, { lang, pick, categoryLabel }) {
  const words = normalise(query).split(' ').filter((w) => w.length > 1);
  if (!words.length) return [];
  return schemes
    .map((s) => {
      const hay = normalise(
        [...['en', lang].flatMap((l) => (s.content[l] ? Object.values(s.content[l]) : [])), pick(s.ministry).text, categoryLabel(s.category), s.slug].join(' '),
      );
      return { s, score: words.reduce((n, w) => n + (hay.includes(w) ? 1 : 0), 0) };
    })
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .map((x) => x.s);
}

/** The text a screen reader would read, for "Listen": drops hidden and control elements. */
export function readableText(root) {
  if (!root) return '';
  const clone = root.cloneNode(true);
  clone.querySelectorAll('[aria-hidden="true"], .visually-hidden, button, select, input, script, style, [data-no-read]').forEach((n) => n.remove());
  return clone.textContent.replace(/\s+/g, ' ').trim();
}
