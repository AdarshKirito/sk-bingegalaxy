/// <reference lib="webworker" />

const CACHE_NAME = 'sk-binge-v1';
const OFFLINE_URL = '/offline.html';

// Assets to pre-cache on install
const PRECACHE = [OFFLINE_URL];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(PRECACHE))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const { request } = event;

  // Only handle GET navigations (HTML pages)
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request).catch(() => caches.match(OFFLINE_URL))
    );
    return;
  }

  // For static assets: Cache-First strategy
  // Skip non-http(s) schemes (e.g. chrome-extension://) which the Cache API rejects
  // Skip Vite dev server modules (contain ?v= or ?t= query params) to avoid caching
  // stale dependency chunks that create duplicate React instances
  if (
    (request.destination === 'style' || request.destination === 'script' || request.destination === 'image' || request.destination === 'font') &&
    request.url.startsWith('http') &&
    !request.url.includes('?v=') &&
    !request.url.includes('?t=')
  ) {
    event.respondWith(
      caches.match(request).then((cached) => {
        if (cached) return cached;
        return fetch(request).then((response) => {
          if (response.ok) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
          }
          return response;
        }).catch(() => new Response('', { status: 503 }));
      })
    );
    return;
  }

  // API calls: Network-only (don't cache dynamic data)
  // Default: just fetch
});
