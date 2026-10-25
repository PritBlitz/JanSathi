// Service worker: the app shell is cached on install so JanSathi opens offline.
// Data (bundles) and locales use network-first, falling back to the cache, so a changed
// source or rule reaches users as soon as they are online. Built assets have hashed names
// and never change, so they are cache-first. The build fills in VERSION and BUILD_ASSETS
// (see the sw plugin in vite.config.js).
const VERSION = 'jansathi-__VERSION__';
const BUILD_ASSETS = __BUILD_ASSETS__;
const SHELL = [
  './',
  'index.html',
  'css/fonts.css',
  'locales/en.json',
  'data/bundle.json',
  'data/bundle.preview.json',
  'img/icon.svg',
  'img/collage.svg',
  'fonts/anton-latin.woff2',
  'fonts/notosans-latin.woff2',
  'manifest.webmanifest',
  ...BUILD_ASSETS,
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSION).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k)))).then(() => self.clients.claim()),
  );
});

const networkFirst = async (request) => {
  const cache = await caches.open(VERSION);
  try {
    const response = await fetch(request);
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch {
    return (await cache.match(request)) || Response.error();
  }
};

const cacheFirst = async (request) => {
  const cache = await caches.open(VERSION);
  const hit = await cache.match(request);
  if (hit) return hit;
  const response = await fetch(request);
  if (response.ok) cache.put(request, response.clone());
  return response;
};

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  // Only this origin; the API (if any) is never cached here: match results are not stored.
  if (event.request.method !== 'GET' || url.origin !== location.origin || url.pathname.includes('/api/')) return;
  const path = url.pathname;
  if (path.includes('/assets/') || path.includes('/fonts/') || path.includes('/img/')) {
    event.respondWith(cacheFirst(event.request));
  } else {
    event.respondWith(networkFirst(event.request));
  }
});
