// Helpers for scheme content: text in the user's language with English fallback.

export const CATEGORY_ICONS = {
  pension: '👵', agriculture: '🌾', health: '🏥', housing: '🏠', education: '🎓', women_children: '🤱', disability: '♿',
  insurance: '🛡️', employment: '⛏️', energy: '🔥', family_support: '🤝', food: '🍚',
};

export const REPORT_URL = 'https://github.com/PritBlitz/JanSathi/issues/new';

export const inEnglish = (s, lang) => !s.content[lang];
export const schemeText = (s, key, lang) => (s.content[lang] || s.content.en)[key];
/** lang="en" on scheme text that fell back to English, so screen readers and fonts switch. */
export const langProps = (s, lang) => (inEnglish(s, lang) ? { lang: 'en' } : {});

export function hostOf(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return '';
  }
}
