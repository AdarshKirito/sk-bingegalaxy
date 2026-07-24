import { describe, it, expect } from 'vitest';
import {
  isPastVisit, pastVisits, countPastVisits, completedVisitSpend, paidVisitCount,
} from '../utils/bookings';

// Mixed past-feed sample: the feed intentionally contains non-visits (cancelled,
// no-show, lapsed) that the history/payments pages need but the visit metrics must
// exclude. This is the exact bug the helpers fix.
const feed = [
  { bookingRef: 'A', status: 'COMPLETED', paymentStatus: 'SUCCESS', totalAmount: 3000 },
  { bookingRef: 'B', status: 'CANCELLED', paymentStatus: 'FAILED', totalAmount: 5000 },
  { bookingRef: 'C', status: 'COMPLETED', paymentStatus: 'SUCCESS', totalAmount: 7000 },
  { bookingRef: 'D', status: 'NO_SHOW', paymentStatus: 'SUCCESS', totalAmount: 4000 },
  { bookingRef: 'E', status: 'CHECKED_IN', paymentStatus: 'SUCCESS', totalAmount: 2000 },
  { bookingRef: 'F', status: 'CONFIRMED', paymentStatus: 'FAILED', totalAmount: 9000 }, // lapsed/unpaid
  { bookingRef: 'G', status: 'COMPLETED', paymentStatus: 'PENDING', totalAmount: 1000 }, // visit, unpaid
];

describe('past-visit helpers', () => {
  it('counts only COMPLETED and CHECKED_IN as visits', () => {
    expect(countPastVisits(feed)).toBe(4); // A, C, E, G
    expect(pastVisits(feed).map((b) => b.bookingRef)).toEqual(['A', 'C', 'E', 'G']);
  });

  it('excludes cancelled, no-show and lapsed bookings', () => {
    expect(isPastVisit({ status: 'CANCELLED' })).toBe(false);
    expect(isPastVisit({ status: 'NO_SHOW' })).toBe(false);
    expect(isPastVisit({ status: 'CONFIRMED' })).toBe(false);
    expect(isPastVisit({ status: 'PENDING' })).toBe(false);
  });

  it('counts genuine visits', () => {
    expect(isPastVisit({ status: 'COMPLETED' })).toBe(true);
    expect(isPastVisit({ status: 'CHECKED_IN' })).toBe(true);
  });

  it('spend requires BOTH a visit and a successful payment', () => {
    // A (3000) + C (7000) + E (2000); G is a visit but unpaid, D was paid but a
    // no-show, B was cancelled — all excluded.
    expect(completedVisitSpend(feed)).toBe(12000);
  });

  it('paid-visit count excludes paid non-visits and unpaid visits', () => {
    expect(paidVisitCount(feed)).toBe(3); // A, C, E
  });

  it('is null/empty safe', () => {
    expect(countPastVisits(null)).toBe(0);
    expect(countPastVisits(undefined)).toBe(0);
    expect(completedVisitSpend([])).toBe(0);
    expect(isPastVisit(null)).toBe(false);
  });
});
