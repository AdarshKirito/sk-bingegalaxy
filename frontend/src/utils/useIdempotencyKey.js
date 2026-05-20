import { useRef, useCallback } from 'react';

/**
 * Stable idempotency-key holder for one logical user action.
 *
 * Why this exists
 * ---------------
 * The axios request interceptor (services/api.js) auto-attaches a fresh
 * UUID `Idempotency-Key` on every POST/PUT/PATCH/DELETE.  That protects
 * axios-level retries (same config object → same key), but **not**
 * user-level retries: if a "Pay" or "Confirm Booking" click times out
 * on the wire and the user clicks again, the second click is a brand
 * new axios call with a brand new key, so the backend treats it as a
 * distinct request and you get a duplicate booking / duplicate charge.
 *
 * This hook gives the component a stable key that:
 *   - is generated lazily on the first `headers()` call,
 *   - is reused for every subsequent attempt (so the server's
 *     IdempotencyService dedupes the retry and replays the cached
 *     response),
 *   - is rotated explicitly on success / permanent failure so the
 *     *next* user action starts a fresh idempotency window.
 *
 * Usage
 * -----
 *   const idem = useIdempotencyKey();
 *   const onSubmit = async () => {
 *     try {
 *       await api.createBooking(payload, { headers: idem.headers() });
 *       idem.rotate(); // success — next click uses a fresh key
 *     } catch (err) {
 *       // KEEP the key so a follow-up retry hits the same server slot.
 *       // Rotate only on permanent failures the user must re-author.
 *       if (err?.response?.status === 422) idem.rotate();
 *       throw err;
 *     }
 *   };
 */
export default function useIdempotencyKey() {
  const keyRef = useRef(null);

  const ensure = useCallback(() => {
    if (!keyRef.current) {
      keyRef.current = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : `idem-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
    }
    return keyRef.current;
  }, []);

  const headers = useCallback(() => ({ 'Idempotency-Key': ensure() }), [ensure]);

  const rotate = useCallback(() => { keyRef.current = null; }, []);

  return { headers, rotate, peek: () => keyRef.current };
}

/**
 * Map-keyed variant for components that drive multiple parallel actions
 * (e.g. MyBookings cancelling/rescheduling different bookingRefs).  Each
 * `slotKey` (typically the bookingRef) gets its own stable idempotency key.
 */
export function useIdempotencyKeyMap() {
  const mapRef = useRef(new Map());

  const headers = useCallback((slotKey) => {
    if (!mapRef.current.has(slotKey)) {
      const uuid = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : `idem-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
      mapRef.current.set(slotKey, uuid);
    }
    return { 'Idempotency-Key': mapRef.current.get(slotKey) };
  }, []);

  const rotate = useCallback((slotKey) => { mapRef.current.delete(slotKey); }, []);

  return { headers, rotate };
}
