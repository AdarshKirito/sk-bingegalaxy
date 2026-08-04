// V81 — turnover buffers on the client.
//
// A booking blocks more of the calendar than it bills for: venues configure
// setup time (prep, decoration) before a booking and cleanup time (reset,
// clean) after it. The server calls that span the OCCUPANCY WINDOW and refuses
// any reservation that overlaps one.
//
// The slot grid therefore has to grey out slots against the occupancy window,
// not the billable interval. Rendering the billable interval instead produces a
// picker whose choices the server rejects at checkout — the customer picks
// 22:00, waits through the hold countdown, and is told it conflicts.
//
// `BookedSlotDto` carries both:
//   startMinute / durationMinutes      -> billable interval (what the guest booked)
//   occupancyStartMinute / occupancyEndMinute -> what the room is unavailable for
//
// Older payloads (and any cached response from before V81) omit the occupancy
// fields; we fall back to the billable interval, which is exactly the pre-V81
// behaviour and is never *less* blocking than the raw duration.

/** Half-hour grid index for a minute-of-day. Floors, so a partial half-hour still counts. */
export const halfHourIndex = (minute) => Math.floor(minute / 30);

/**
 * The [start, end) minute range a booked slot makes unavailable.
 * Falls back to the billable interval when the server did not send buffers.
 */
export function occupancyRange(bookedSlot) {
  const start = bookedSlot.startMinute != null ? bookedSlot.startMinute : 0;
  const duration = bookedSlot.durationMinutes != null
    ? bookedSlot.durationMinutes
    : (bookedSlot.durationHours || 0) * 60;

  const hasBuffers = bookedSlot.occupancyStartMinute != null
    && bookedSlot.occupancyEndMinute != null;

  return hasBuffers
    ? { start: bookedSlot.occupancyStartMinute, end: bookedSlot.occupancyEndMinute }
    : { start, end: start + duration };
}

/**
 * Every half-hour grid index a booked slot touches, buffers included.
 *
 * A buffer need not land on a 30-minute boundary (a 45-minute cleanup after a
 * 22:00 finish runs to 22:45), so the range is expanded outward: the start
 * index floors and the end index covers any partially-touched half-hour. Under-
 * blocking here would re-open the exact gap the buffer exists to close.
 *
 * Returns an empty array for a zero-length window.
 */
export function occupiedHalfHours(bookedSlot) {
  const { start, end } = occupancyRange(bookedSlot);
  if (end <= start) return [];

  const indices = [];
  const firstIdx = halfHourIndex(start);
  const lastIdx = halfHourIndex(end - 1); // end is exclusive
  for (let idx = firstIdx; idx <= lastIdx; idx++) {
    if (idx >= 0) indices.push(idx);      // a buffer may reach before midnight
  }
  return indices;
}
