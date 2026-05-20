// Tiny pub/sub used by the customer hub pages (MyBookings, CustomerPayments,
// dashboard) to live-refresh their cached lists when *anywhere else in the app*
// a booking or payment mutation succeeds.
//
// Pattern parallel:  Stripe Dashboard / Linear / Notion all keep their list
// views up to date by piggy-backing on a lightweight in-process bus instead of
// a full WebSocket — good enough latency for "I just created a reservation"
// flows, with zero infra cost.
//
// Combined with:
//   1. window 'focus' + document 'visibilitychange' refetch
//   2. light interval polling (every 20s) while the tab is visible
//   3. refetch on react-router location change
// the result is that newly-created reservations appear on the My Bookings /
// Payments tabs without the customer ever having to hit Refresh.

const bus = typeof window !== 'undefined' ? new EventTarget() : null;

const EVENT_NAME = 'skbg:booking-changed';

/**
 * Broadcast that a booking / payment mutation has occurred so any subscribed
 * list views can refetch.  Caller passes an optional reason string ("created",
 * "cancelled", "paid", …) used only for logging / debugging.
 */
export function emitBookingsChanged(reason = 'unknown', detail = {}) {
  if (!bus) return;
  bus.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: { reason, ...detail } }));
}

/**
 * Subscribe to mutation events.  Returns an unsubscribe fn — wire it from a
 * useEffect cleanup.
 */
export function onBookingsChanged(handler) {
  if (!bus || typeof handler !== 'function') return () => {};
  const wrapped = (event) => {
    try { handler(event.detail); } catch { /* never let a subscriber error escape */ }
  };
  bus.addEventListener(EVENT_NAME, wrapped);
  return () => bus.removeEventListener(EVENT_NAME, wrapped);
}
