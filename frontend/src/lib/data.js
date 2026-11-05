// Loads the rule bundle. With VITE_API_BASE set (the dev server sets it, see vite.config.js)
// the Spring Boot API is tried first; the static files in public/data are always the fallback,
// so the PWA works with no backend and offline. Matching always runs on the device (rules.js).

const API_BASE = import.meta.env.VITE_API_BASE;
const base = import.meta.env.BASE_URL;

async function fetchJson(url, init) {
  const res = await fetch(url, init);
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return res;
}

export const hasApi = () => API_BASE !== undefined;
export const apiUrl = (path) => `${API_BASE ?? ''}/api/v1${path}`;

/** Returns {bundle, preview, source: 'api' | 'static'}. */
export async function loadBundle({ wantPreview }) {
  if (hasApi() && !wantPreview) {
    try {
      const res = await fetchJson(apiUrl('/rules/bundle'));
      const bundle = await res.json();
      return { bundle, preview: res.headers.get('X-JanSathi-Preview') === 'true' || bundle.include_drafts, source: 'api' };
    } catch {
      /* API not running: use the static bundle */
    }
  }
  if (!wantPreview) {
    try {
      const bundle = await (await fetchJson(`${base}data/bundle.json`)).json();
      // Until a reviewer publishes schemes the production bundle is empty: fall back to drafts and say so.
      if (bundle.schemes.length || Object.keys(bundle.states || {}).length) return { bundle, preview: false, source: 'static' };
    } catch {
      /* fall through to the preview bundle */
    }
  }
  const bundle = await (await fetchJson(`${base}data/bundle.preview.json`)).json();
  return { bundle, preview: true, source: 'static' };
}

/** Machine-translated scheme text for a language; null when there is none. */
export async function loadTranslations(lang) {
  try {
    return await (await fetchJson(`${base}data/translations/${lang}.json`)).json();
  } catch {
    return null;
  }
}

/** One state's schemes (public/data/states/<code>.json, or .preview.json while in preview). */
export async function loadStateSchemes(code, preview) {
  const res = await fetchJson(`${base}data/states/${code}${preview ? '.preview' : ''}.json`);
  return (await res.json()).schemes;
}

/** POST /api/v1/reports. Returns false when the API is not available, so the caller can fall back. */
export async function reportApi(report) {
  if (!hasApi()) return false;
  try {
    await fetchJson(apiUrl('/reports'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(report) });
    return true;
  } catch {
    return false;
  }
}

/** Anonymous outcome (brief §6.7): sent only when the person taps share. Carries no identity. */
export async function shareOutcome(outcome) {
  if (!hasApi()) return false;
  try {
    await fetchJson(apiUrl('/outcomes'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(outcome) });
    return true;
  } catch {
    return false;
  }
}

/** What people reported for a scheme; the server answers only when at least 10 did. */
export async function loadOutcomes(slug) {
  if (!hasApi()) return null;
  try {
    return await (await fetchJson(apiUrl(`/schemes/${slug}/outcomes`))).json();
  } catch {
    return null;
  }
}

/** POST /api/v1/ask: cited passages for a free question. Null when the API is not available. */
export async function askApi(question, lang) {
  if (!hasApi()) return null;
  try {
    const res = await fetchJson(apiUrl('/ask'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, lang }),
    });
    return await res.json();
  } catch {
    return null;
  }
}
