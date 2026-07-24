/* push-sw.js
 *
 * Imported into the Workbox-generated service worker via vite.config.js
 * (workbox.importScripts). It layers Web Push (RFC-8291) display + click handling on
 * top of Workbox's precaching/routing, WITHOUT owning the SW lifecycle — Workbox keeps
 * control of install/activate/fetch. Kept dependency-free so importScripts stays cheap.
 */
/* eslint-disable no-restricted-globals */

// ── SEC-009 remediation: purge the legacy runtime API cache ──────────────
// Older service-worker builds ran a NetworkFirst 'api-cache' over authenticated
// booking/admin endpoints, keyed by URL only — it could replay one identity's
// data to another after a user/binge switch. The current build caches no API
// responses at all, but clients that installed the old SW still carry the
// poisoned cache until it is explicitly deleted here on activate.
self.addEventListener('activate', (event) => {
  event.waitUntil(caches.delete('api-cache'));
});

self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch (_e) {
    payload = { title: 'SK Binge Galaxy', body: event.data ? event.data.text() : '' };
  }

  const title = payload.title || 'SK Binge Galaxy';
  const options = {
    body: payload.body || '',
    icon: '/favicon.svg',
    badge: '/favicon.svg',
    // A tag collapses repeats (e.g. multiple messages) into one entry; renotify makes
    // the device still buzz on an update rather than silently replacing.
    tag: payload.tag || undefined,
    renotify: Boolean(payload.tag),
    data: { url: payload.url || '/', type: payload.type || '' },
    timestamp: Date.now(),
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  const targetUrl = data.url || '/';

  event.waitUntil((async () => {
    const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    // Prefer focusing an app tab that's already open; navigate it to the target if given.
    for (const client of clientList) {
      if ('focus' in client) {
        try {
          if (targetUrl && targetUrl !== '/' && 'navigate' in client) {
            await client.navigate(targetUrl).catch(() => {});
          }
          return client.focus();
        } catch (_e) {
          /* fall through to opening a fresh window */
        }
      }
    }
    if (self.clients.openWindow) {
      return self.clients.openWindow(targetUrl);
    }
    return undefined;
  })());
});
