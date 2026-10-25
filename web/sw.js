// Service worker: the app shell is cached on install so JanSathi opens offline.
// Data (bundles) and locales use network-first, falling back to the cache, so a
// changed source or rule reaches users as soon as they are online.
const VERSION = 'jansathi-v1';
const SHELL = [
  './',
  'index.html',
  'css/styles.css',
  'css/fonts.css',
  'js/app.js',
  'js/i18n.js',
  'js/speech.js',
  'js/rules.js',
  'locales/en.json',
  'data/bundle.json',
  'data/bundle.preview.json',
  'img/icon.svg',
  'img/collage.svg',
  'fonts/anton-latin.woff2',
  'fonts/notosans-latin.woff2',
  'manifest.webmanifest',
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
  if (event.request.method !== 'GET' || url.origin !== location.origin) return;
  const path = url.pathname;
  if (path.includes('/data/') || path.includes('/locales/') || path.endsWith('.js') || path.endsWith('.css') || path.endsWith('/') || path.endsWith('.html')) {
    event.respondWith(networkFirst(event.request));
  } else {
    event.respondWith(cacheFirst(event.request));
  }
});
