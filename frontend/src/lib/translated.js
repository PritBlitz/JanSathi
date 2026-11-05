// Machine-translated scheme text (content/translations, brief §17). It is laid over a copy of the
// bundle for display only: scheme files stay as reviewed, rule logic is never touched, and any text
// a translation lacks falls back to English. Every translated scheme carries machine_translated.

function overlayScheme(scheme, entry, lang) {
  const { rules: ruleText = {}, ...text } = entry;
  const content = { ...scheme.content.en };
  for (const k of ['name', 'summary', 'benefit', 'how_to_apply']) if (text[k]) content[k] = text[k];
  return {
    ...scheme,
    content: { ...scheme.content, [lang]: { ...content, machine_translated: true } },
    rules: scheme.rules.map((r) => (ruleText[r.code] ? { ...r, text: { ...r.text, [lang]: ruleText[r.code] } } : r)),
  };
}

function overlayDocument(doc, entry, lang) {
  const out = { ...doc };
  for (const k of ['name', 'how_to', 'office']) if (entry[k] && doc[k]) out[k] = { ...doc[k], [lang]: entry[k] };
  return out;
}

/** Returns a bundle-shaped object with this language's translations applied. */
export function applyTranslations(bundle, translations, lang) {
  if (!translations) return bundle;
  const schemes = bundle.schemes.map((s) => (translations.schemes?.[s.slug] && !s.content[lang] ? overlayScheme(s, translations.schemes[s.slug], lang) : s));
  const docs = { ...bundle.documents.documents };
  for (const [code, entry] of Object.entries(translations.documents || {})) if (docs[code]) docs[code] = overlayDocument(docs[code], entry, lang);
  return { ...bundle, schemes, documents: { ...bundle.documents, documents: docs } };
}

export const isMachineTranslated = (scheme, lang) => !!scheme.content[lang]?.machine_translated;
