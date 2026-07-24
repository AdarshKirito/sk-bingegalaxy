/**
 * Booking-history helpers with ONE authoritative definition of a "past visit".
 *
 * The `/bookings/my/past` feed is a booking-HISTORY feed: it deliberately returns
 * CANCELLED, NO_SHOW and lapsed/unpaid past-dated bookings so the My Bookings and
 * Payments pages can show a complete record. That is correct for those pages — but
 * a dashboard tile labelled "Past Visits" / "Completed visits" must not count them.
 * Counting a cancelled booking or one whose payment never went through as a "visit"
 * is what this module exists to prevent, and keeping the rule here means the
 * dashboard and the account centre can never drift apart on it.
 *
 * A past visit = the customer actually attended:
 *   - COMPLETED   — the visit happened and was closed out, or
 *   - CHECKED_IN  — they were checked in; a row in this state only reaches the
 *                   PAST feed once its date is in the past (today's checked-in
 *                   bookings are in the CURRENT feed), so it represents a real
 *                   past attendance that simply was not formally completed.
 *
 * Explicitly NOT visits: CANCELLED, NO_SHOW, and any still-PENDING/CONFIRMED
 * past-dated booking (the no-show automation converts genuine no-shows to NO_SHOW;
 * anything left in PENDING/CONFIRMED never had confirmed attendance, and typically
 * never completed payment).
 */

const PAST_VISIT_STATUSES = new Set(['COMPLETED', 'CHECKED_IN']);

/** True when a booking from the past feed represents an actual attended visit. */
export function isPastVisit(booking) {
  return !!booking && PAST_VISIT_STATUSES.has(booking.status);
}

/** The subset of a past-bookings list that are real visits. */
export function pastVisits(list) {
  return (Array.isArray(list) ? list : []).filter(isPastVisit);
}

/** Count of real past visits. */
export function countPastVisits(list) {
  return pastVisits(list).length;
}

/**
 * Total amount actually spent on completed visits.
 *
 * Both conditions are required: the booking must be a real visit AND its payment
 * must have succeeded. Filtering on payment alone (the previous behaviour) counted
 * a cancelled-but-charged or refunded booking as spend; filtering on visit alone
 * would count a completed visit whose payment is still pending.
 */
export function completedVisitSpend(list) {
  return pastVisits(list)
    .filter((b) => b.paymentStatus === 'SUCCESS')
    .reduce((sum, b) => sum + (Number(b.totalAmount) || 0), 0);
}

/** Count of completed visits that were successfully paid. */
export function paidVisitCount(list) {
  return pastVisits(list).filter((b) => b.paymentStatus === 'SUCCESS').length;
}
