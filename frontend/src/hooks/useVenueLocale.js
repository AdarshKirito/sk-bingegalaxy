import { useCallback, useMemo } from 'react';
import { useBinge } from '../context/BingeContext';
import { formatCurrency, currencySymbol, resolveCurrency } from '../utils/currency';
import { formatInZone, formatDateInZone, formatTimeInZone, todayInZone } from '../services/timeFormat';

/**
 * Venue-scoped money + time formatting, bound to the SELECTED binge.
 *
 * Once a venue has a timezone and currency assigned, everything about that
 * venue must render in THAT zone and THAT currency — never the viewer's
 * browser zone or a hardcoded ₹. This hook is the one place pages get those
 * formatters from:
 *
 *   const { money, dateTime, timezone, currency } = useVenueLocale();
 *   money(1234.5)        → "$1,234.50" for a US venue, "₹1,234.50" for IN
 *   dateTime(createdAt)  → "21 Apr 2026, 12:45 AM" in the VENUE's zone
 *
 * Per-amount currency overrides (e.g. a booking row that carries its own
 * paymentCurrencyCode) can be passed as the source: money(amt, row).
 */
export function useVenueLocale() {
  const { selectedBinge } = useBinge();

  const currency = resolveCurrency(selectedBinge);
  const timezone = selectedBinge?.timezone || 'Asia/Kolkata';

  const money = useCallback(
    (amount, source, opts) =>
      formatCurrency(amount, source ? resolveCurrency(source) : currency, opts),
    [currency]
  );

  const symbol = useMemo(() => currencySymbol(currency), [currency]);

  const dateTime = useCallback((v, opts) => formatInZone(v, timezone, opts), [timezone]);
  const date = useCallback((v, opts) => formatDateInZone(v, timezone, opts), [timezone]);
  const time = useCallback((v, opts) => formatTimeInZone(v, timezone, opts), [timezone]);
  const today = useCallback(() => todayInZone(timezone), [timezone]);

  return { currency, symbol, timezone, money, dateTime, date, time, today, binge: selectedBinge };
}

export default useVenueLocale;
