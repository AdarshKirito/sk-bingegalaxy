// Shared display formatters for booking date/time/duration.
// Backend stores times in 24h "HH:mm" format; display in 12h with AM/PM.

/**
 * Format a "HH:mm" or "HH:mm:ss" 24-hour time string as 12-hour with AM/PM.
 * Examples: "14:00" -> "2:00 PM", "09:30" -> "9:30 AM", "00:15" -> "12:15 AM".
 * Returns the input unchanged if it cannot be parsed.
 */
export const formatTime12h = (time) => {
  if (time == null || time === '') return '';
  const str = String(time);
  const m = str.match(/^(\d{1,2}):(\d{2})/);
  if (!m) return str;
  let h = parseInt(m[1], 10);
  const min = m[2];
  if (Number.isNaN(h) || h < 0 || h > 23) return str;
  const period = h >= 12 ? 'PM' : 'AM';
  h = h % 12;
  if (h === 0) h = 12;
  return `${h}:${min} ${period}`;
};

/** Format a minutes-since-midnight number (e.g. 870) as 12-hour AM/PM ("2:30 PM"). */
export const formatMinutesAsTime12h = (totalMinutes) => {
  const m = Number(totalMinutes);
  if (!Number.isFinite(m)) return '';
  const hh = String(Math.floor(m / 60) % 24).padStart(2, '0');
  const mm = String(m % 60).padStart(2, '0');
  return formatTime12h(`${hh}:${mm}`);
};

/**
 * Render a start–end window in 12-hour format from a "HH:mm" start and a duration in minutes.
 * Example: ("10:00", 150) -> "10:00 AM – 12:30 PM".
 */
export const formatTimeRange12h = (startHHmm, durationMinutes) => {
  if (!startHHmm) return '';
  const parts = String(startHHmm).split(':');
  const startMin = parseInt(parts[0], 10) * 60 + parseInt(parts[1] || '0', 10);
  const endMin = startMin + (Number(durationMinutes) || 0);
  return `${formatMinutesAsTime12h(startMin)} – ${formatMinutesAsTime12h(endMin)}`;
};

/** Format a duration in minutes as "Xh Ym" / "Xh" / "Ym". */
export const formatDurationMinutes = (minutes) => {
  const m = Number(minutes) || 0;
  if (!m) return '';
  const h = Math.floor(m / 60);
  const rem = m % 60;
  if (h > 0 && rem > 0) return `${h}h ${rem}m`;
  if (h > 0) return `${h}h`;
  return `${rem}m`;
};

/**
 * Format a Date / ISO datetime string as locale 12-hour clock time ("h:mm AM/PM").
 * Returns '' for falsy input.
 */
export const formatDateTime12h = (input) => {
  if (!input) return '';
  const d = input instanceof Date ? input : new Date(input);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', hour12: true });
};
