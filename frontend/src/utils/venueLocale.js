import useBingeStore from '../stores/bingeStore';
import { formatCurrency, currencySymbol, resolveCurrency } from './currency';
import { formatInZone, formatDateInZone, formatTimeInZone, todayInZone } from '../services/timeFormat';

/**
 * Venue-scoped money + time formatting bound to the SELECTED binge —
 * plain-function twin of the useVenueLocale() hook.
 *
 * Once a venue has a currency and timezone assigned, everything about that
 * venue renders in THAT currency and THAT zone — never a hardcoded ₹ or the
 * viewer's browser timezone. These read the zustand store directly so they
 * work outside component render too (toasts, confirm() copy, template-string
 * receipts, CSV exports).
 *
 * Not reactive: a component that must re-render when the admin switches venue
 * should use the useVenueLocale() hook instead. In practice switching venue
 * navigates (remounts the page), so these are safe for page-level rendering.
 */

function selectedBinge() {
  try { return useBingeStore.getState().selectedBinge; } catch { return null; }
}

/** The selected venue's ISO-4217 currency code (falls back to INR). */
export function venueCurrency(source) {
  return resolveCurrency(source || selectedBinge());
}

/**
 * Format an amount in the selected venue's currency.
 * Pass `source` (a booking/payment/binge carrying its own currency code) to
 * override, e.g. venueMoney(row.amount, row).
 */
export function venueMoney(amount, source, opts) {
  return formatCurrency(amount, venueCurrency(source), opts);
}

/** Bare currency symbol for the selected venue (e.g. "₹", "$"). */
export function venueSymbol(source) {
  return currencySymbol(venueCurrency(source));
}

/** The selected venue's IANA timezone (falls back to the platform zone). */
export function venueTimezone() {
  return selectedBinge()?.timezone || 'Asia/Kolkata';
}

/** Server timestamp → "21 Apr 2026, 10:15 AM" in the venue's zone. */
export function venueDateTime(value, opts) {
  return formatInZone(value, venueTimezone(), opts);
}

/** Server timestamp → "21 Apr 2026" in the venue's zone. */
export function venueDate(value, opts) {
  return formatDateInZone(value, venueTimezone(), opts);
}

/** Server timestamp → "10:15 AM" in the venue's zone. */
export function venueTime(value, opts) {
  return formatTimeInZone(value, venueTimezone(), opts);
}

/** The venue's calendar "today" as YYYY-MM-DD. */
export function venueToday() {
  return todayInZone(venueTimezone());
}
