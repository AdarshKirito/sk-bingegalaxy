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

// ── Venue-timezone display ──────────────────────────────────────────────────
// Booking dates/times are venue-LOCAL wall-clock values (no UTC conversion). These
// helpers let the UI label them with the venue's zone so a customer in another
// timezone is never confused about which clock a time refers to. All are DST-aware
// (offsets are resolved at the specific instant) and fail soft to '' / null.

/**
 * Today's date (YYYY-MM-DD) in the VENUE's timezone. The booking wizard must compute
 * "today" and past-slot filtering in the venue's zone — not the browser's — so a customer
 * in a different timezone sees exactly what the backend (which validates venue-locally)
 * allows. Falls back to the browser's local date if the zone can't be resolved.
 */
export const venueLocalTodayISO = (timeZone, at = new Date()) => {
  try {
    if (!timeZone) throw new Error('no tz');
    // en-CA yields ISO YYYY-MM-DD; formatting in the target zone gives that zone's date.
    return new Intl.DateTimeFormat('en-CA', {
      timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    }).format(at);
  } catch {
    const y = at.getFullYear();
    const m = String(at.getMonth() + 1).padStart(2, '0');
    const d = String(at.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }
};

/** Minutes-since-midnight of "now" in the VENUE's timezone (browser-local fallback). */
export const venueLocalNowMinutes = (timeZone, at = new Date()) => {
  try {
    if (!timeZone) throw new Error('no tz');
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone, hourCycle: 'h23', hour: '2-digit', minute: '2-digit',
    }).formatToParts(at);
    let hh = Number(parts.find((p) => p.type === 'hour')?.value);
    const mm = Number(parts.find((p) => p.type === 'minute')?.value);
    if (hh === 24) hh = 0;
    if (Number.isNaN(hh) || Number.isNaN(mm)) throw new Error('parse');
    return hh * 60 + mm;
  } catch {
    return at.getHours() * 60 + at.getMinutes();
  }
};

/** Offset label of an IANA zone at an instant, e.g. "GMT+5:30". '' if unresolvable. */
export const venueZoneOffset = (timeZone, at = new Date()) => {
  if (!timeZone) return '';
  try {
    return new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'shortOffset' })
      .formatToParts(at).find((p) => p.type === 'timeZoneName')?.value || '';
  } catch { return ''; }
};

/** Alphabetic abbreviation ("IST","EST") if the engine exposes one, else ''. */
export const venueZoneAbbrev = (timeZone, at = new Date()) => {
  if (!timeZone) return '';
  try {
    const v = new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'short' })
      .formatToParts(at).find((p) => p.type === 'timeZoneName')?.value || '';
    // Keep only real letter-abbreviations — many engines return a "GMT+5:30" here too.
    return /^[A-Za-z]{2,5}$/.test(v) ? v : '';
  } catch { return ''; }
};

/** Compact zone label: "IST (GMT+5:30)", or "GMT+5:30", or the raw id as last resort. */
export const venueZoneLabel = (timeZone, at = new Date()) => {
  if (!timeZone) return '';
  const abbrev = venueZoneAbbrev(timeZone, at);
  const offset = venueZoneOffset(timeZone, at);
  if (abbrev && offset) return `${abbrev} (${offset})`;
  return abbrev || offset || timeZone;
};

/** Offset in minutes east of UTC for an IANA zone at a given instant. null on failure. */
const zoneOffsetMinutes = (timeZone, at) => {
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone, hourCycle: 'h23',
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    }).formatToParts(at);
    const map = {};
    for (const p of parts) map[p.type] = p.value;
    let hour = Number(map.hour);
    if (hour === 24) hour = 0; // some engines emit '24' for midnight
    const asUTC = Date.UTC(+map.year, +map.month - 1, +map.day, hour, +map.minute, +map.second);
    if (Number.isNaN(asUTC)) return null;
    return Math.round((asUTC - at.getTime()) / 60000);
  } catch { return null; }
};

/**
 * Convert a venue-local wall-clock (dateStr "YYYY-MM-DD", timeStr "HH:mm[:ss]") in
 * `timeZone` into how it reads in the VIEWER's own timezone, e.g. "Jul 2, 11:30 PM".
 * Returns null when the viewer's zone matches the venue's (nothing to disambiguate),
 * or on any parse failure — so callers can just skip rendering the hint.
 */
export const viewerLocalEquivalent = (dateStr, timeStr, timeZone) => {
  try {
    if (!dateStr || !timeStr || !timeZone) return null;
    const viewerZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (!viewerZone || viewerZone === timeZone) return null;

    const [y, mo, d] = String(dateStr).split('-').map(Number);
    const tp = String(timeStr).split(':');
    const hh = Number(tp[0]);
    const mm = Number(tp[1] || 0);
    if ([y, mo, d, hh, mm].some((n) => Number.isNaN(n))) return null;

    // Wall-clock → absolute instant: assume the numbers are UTC, then subtract the
    // venue's offset. Refine once to absorb any DST-transition offset shift.
    const guessUTC = Date.UTC(y, mo - 1, d, hh, mm);
    let offsetMin = zoneOffsetMinutes(timeZone, new Date(guessUTC));
    if (offsetMin == null) return null;
    let instant = new Date(guessUTC - offsetMin * 60000);
    const refined = zoneOffsetMinutes(timeZone, instant);
    if (refined != null && refined !== offsetMin) {
      offsetMin = refined;
      instant = new Date(guessUTC - refined * 60000);
    }
    if (Number.isNaN(instant.getTime())) return null;

    // Same offset in both zones → identical wall clock, no hint needed.
    const viewerOffsetMin = zoneOffsetMinutes(viewerZone, instant);
    if (viewerOffsetMin == null || viewerOffsetMin === offsetMin) return null;

    return new Intl.DateTimeFormat(undefined, {
      timeZone: viewerZone, month: 'short', day: 'numeric',
      hour: 'numeric', minute: '2-digit', hour12: true,
    }).format(instant);
  } catch { return null; }
};
