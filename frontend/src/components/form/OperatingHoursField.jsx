import { FiCopy } from 'react-icons/fi';
import './FormFields.css';

/**
 * Per-day operating-hours editor for the admin binge form (Google-Business-Hours
 * style). Each weekday can be Open (with its own open/close time) or Closed.
 * Bookings on a closed day, or outside that day's window, are rejected server-side.
 *
 * `value` is an array of `{ dayOfWeek: 1..7, closed: bool, openTime: "HH:mm",
 * closeTime: "HH:mm" }` (1 = Monday … 7 = Sunday), matching the backend
 * `BingeDayHours` contract. `onChange(nextArray)` replaces the whole list.
 */
const DAYS = [
  { dow: 1, label: 'Monday' },
  { dow: 2, label: 'Tuesday' },
  { dow: 3, label: 'Wednesday' },
  { dow: 4, label: 'Thursday' },
  { dow: 5, label: 'Friday' },
  { dow: 6, label: 'Saturday' },
  { dow: 7, label: 'Sunday' },
];

/** Build a full open-every-day schedule from a single default open/close pair. */
export function defaultOpeningHours(openTime = '10:00', closeTime = '23:00') {
  return DAYS.map((d) => ({ dayOfWeek: d.dow, closed: false, openTime, closeTime }));
}

/** Normalize an arbitrary (possibly partial or unordered) list into all 7 ordered days. */
export function normalizeOpeningHours(list, openTime = '10:00', closeTime = '23:00') {
  const byDow = new Map((list || []).map((d) => [d.dayOfWeek, d]));
  return DAYS.map((d) => {
    const existing = byDow.get(d.dow);
    if (!existing) return { dayOfWeek: d.dow, closed: false, openTime, closeTime };
    return {
      dayOfWeek: d.dow,
      closed: !!existing.closed,
      openTime: (existing.openTime || openTime).slice(0, 5),
      closeTime: (existing.closeTime || closeTime).slice(0, 5),
    };
  });
}

/** Client-side validation mirroring the server: every open day needs close > open. */
export function validateOpeningHours(list) {
  for (const d of list || []) {
    if (d.closed) continue;
    if (!d.openTime || !d.closeTime) {
      return `${DAYS.find((x) => x.dow === d.dayOfWeek)?.label || 'A day'} is open — set both times or mark it closed.`;
    }
    if (d.closeTime <= d.openTime) {
      return `${DAYS.find((x) => x.dow === d.dayOfWeek)?.label || 'A day'} closing time must be after its opening time.`;
    }
  }
  return '';
}

export default function OperatingHoursField({ value, onChange, disabled = false }) {
  const days = value && value.length ? value : defaultOpeningHours();

  const setDay = (dow, patch) =>
    onChange(days.map((d) => (d.dayOfWeek === dow ? { ...d, ...patch } : d)));

  const copyMondayToAll = () => {
    const mon = days.find((d) => d.dayOfWeek === 1) || days[0];
    onChange(days.map((d) => ({ ...d, closed: mon.closed, openTime: mon.openTime, closeTime: mon.closeTime })));
  };

  return (
    <fieldset className="address-fields" disabled={disabled}>
      <legend className="address-fields-legend">Operating days &amp; hours</legend>
      <p className="address-fields-help">
        Set when the venue is open each day. Bookings on a closed day, or outside that
        day&apos;s hours, are rejected automatically.
      </p>

      <div className="oh-toolbar">
        <button type="button" className="btn btn-secondary btn-sm" onClick={copyMondayToAll} disabled={disabled}>
          <FiCopy /> Copy Monday to all days
        </button>
      </div>

      <div className="oh-list">
        {days.map((d) => {
          const label = DAYS.find((x) => x.dow === d.dayOfWeek)?.label || `Day ${d.dayOfWeek}`;
          return (
            <div key={d.dayOfWeek} className={`oh-row ${d.closed ? 'oh-row-closed' : ''}`}>
              <span className="oh-day">{label}</span>
              <label className="oh-toggle">
                <input
                  type="checkbox"
                  checked={!d.closed}
                  onChange={(e) => setDay(d.dayOfWeek, { closed: !e.target.checked })}
                  disabled={disabled}
                />
                <span>{d.closed ? 'Closed' : 'Open'}</span>
              </label>
              {!d.closed ? (
                <div className="oh-times">
                  <input
                    type="time" value={d.openTime || ''} disabled={disabled}
                    onChange={(e) => setDay(d.dayOfWeek, { openTime: e.target.value })}
                  />
                  <span className="oh-sep">to</span>
                  <input
                    type="time" value={d.closeTime || ''} disabled={disabled}
                    onChange={(e) => setDay(d.dayOfWeek, { closeTime: e.target.value })}
                  />
                </div>
              ) : (
                <div className="oh-times oh-times-closed">Closed all day</div>
              )}
            </div>
          );
        })}
      </div>
    </fieldset>
  );
}
