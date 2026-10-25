// Hash router: #/name/arg?query. Hash URLs keep the PWA working on any static host and offline.
import { useEffect, useState } from 'react';

export function parseHash(hash = location.hash) {
  const raw = hash.replace(/^#\/?/, '');
  const [path, query = ''] = raw.split('?');
  const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
  return { name: parts[0] || '', arg: parts[1], query: new URLSearchParams(query) };
}

export function useRoute() {
  const [route, setRoute] = useState(() => parseHash());
  useEffect(() => {
    const on = () => setRoute(parseHash());
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}

export const go = (href) => {
  location.hash = href.replace(/^#/, '');
};
