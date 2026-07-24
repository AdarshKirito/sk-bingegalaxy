/**
 * Browser Web Push (VAPID) client helper.
 *
 * Bridges the PWA service worker's PushManager to the notification-service subscription
 * API. The service worker itself is the Workbox-generated one with our push handlers
 * layered in via public/push-sw.js (see vite.config.js). Every function is defensive:
 * push is a progressive enhancement, so unsupported browsers / denied permission resolve
 * to a state object rather than throwing.
 */
import { notificationService } from './endpoints';

export const PUSH_STATE = {
  UNSUPPORTED: 'unsupported',
  DENIED: 'denied',
  DEFAULT: 'default', // supported, not yet subscribed
  SUBSCRIBED: 'subscribed',
  DISABLED_SERVER: 'disabled_server', // backend has no VAPID keys configured
};

export function isPushSupported() {
  return (
    typeof navigator !== 'undefined' &&
    'serviceWorker' in navigator &&
    typeof window !== 'undefined' &&
    'PushManager' in window &&
    'Notification' in window
  );
}

/** VAPID keys arrive base64url-encoded; PushManager needs a Uint8Array. */
function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = window.atob(base64);
  const output = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) output[i] = raw.charCodeAt(i);
  return output;
}

/** Resolve the active SW registration, racing a timeout so a missing SW can't hang the UI. */
async function swReady(timeoutMs = 10000) {
  const existing = await navigator.serviceWorker.getRegistration();
  if (existing && existing.active) return existing;
  return Promise.race([
    navigator.serviceWorker.ready,
    new Promise((_, reject) => setTimeout(() => reject(new Error('Service worker not ready')), timeoutMs)),
  ]);
}

/** Current push state without prompting the user. Safe to call on mount. */
export async function getPushState() {
  if (!isPushSupported()) return PUSH_STATE.UNSUPPORTED;
  if (Notification.permission === 'denied') return PUSH_STATE.DENIED;
  try {
    const reg = await navigator.serviceWorker.getRegistration();
    if (reg) {
      const sub = await reg.pushManager.getSubscription();
      if (sub) return PUSH_STATE.SUBSCRIBED;
    }
  } catch {
    /* ignore — fall through to default */
  }
  return PUSH_STATE.DEFAULT;
}

async function fetchVapidKey() {
  const res = await notificationService.getPushPublicKey();
  const data = res?.data?.data || {};
  return { publicKey: data.publicKey || '', enabled: Boolean(data.enabled) };
}

/**
 * Prompt for permission (if needed) and register a push subscription with the backend.
 * @returns {Promise<string>} the resulting PUSH_STATE
 */
export async function enablePush() {
  if (!isPushSupported()) return PUSH_STATE.UNSUPPORTED;

  const { publicKey, enabled } = await fetchVapidKey();
  if (!enabled || !publicKey) return PUSH_STATE.DISABLED_SERVER;

  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    return permission === 'denied' ? PUSH_STATE.DENIED : PUSH_STATE.DEFAULT;
  }

  const reg = await swReady();
  let sub = await reg.pushManager.getSubscription();

  // If a stale subscription exists for a different (rotated) VAPID key, replace it.
  if (sub) {
    const existingKey = sub.options?.applicationServerKey;
    const wantKey = urlBase64ToUint8Array(publicKey);
    if (!existingKey || !sameKey(new Uint8Array(existingKey), wantKey)) {
      try { await sub.unsubscribe(); } catch { /* ignore */ }
      sub = null;
    }
  }

  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(publicKey),
    });
  }

  await notificationService.subscribePush({
    ...sub.toJSON(),
    userAgent: navigator.userAgent,
  });
  return PUSH_STATE.SUBSCRIBED;
}

/** Unsubscribe locally and tell the backend to forget this endpoint. */
export async function disablePush() {
  if (!isPushSupported()) return PUSH_STATE.UNSUPPORTED;
  try {
    const reg = await navigator.serviceWorker.getRegistration();
    const sub = reg && (await reg.pushManager.getSubscription());
    if (sub) {
      const endpoint = sub.endpoint;
      try { await sub.unsubscribe(); } catch { /* ignore */ }
      try { await notificationService.unsubscribePush(endpoint); } catch { /* ignore */ }
    }
  } catch {
    /* ignore */
  }
  return PUSH_STATE.DEFAULT;
}

/**
 * If the browser is already subscribed and permission is granted, make sure the backend
 * still knows about this endpoint (it may have been pruned, or the user switched devices).
 * Best-effort, silent — call once after login.
 */
export async function syncPushSubscription() {
  if (!isPushSupported() || Notification.permission !== 'granted') return;
  try {
    const reg = await navigator.serviceWorker.getRegistration();
    const sub = reg && (await reg.pushManager.getSubscription());
    if (sub) {
      await notificationService.subscribePush({ ...sub.toJSON(), userAgent: navigator.userAgent });
    }
  } catch {
    /* ignore */
  }
}

function sameKey(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 1) if (a[i] !== b[i]) return false;
  return true;
}
