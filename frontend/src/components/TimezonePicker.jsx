import { useMemo } from 'react';

// Curated fallback for browsers that don't expose Intl.supportedValuesOf
// (older Safari/Firefox). Mirrors the IANA region/city form the backend
// validates with ZoneId.of(...).
const FALLBACK_ZONES = [
  'UTC',
  'Asia/Kolkata', 'Asia/Dubai', 'Asia/Singapore', 'Asia/Tokyo', 'Asia/Shanghai',
  'Asia/Bangkok', 'Asia/Karachi', 'Asia/Dhaka', 'Asia/Colombo', 'Asia/Riyadh',
  'Europe/London', 'Europe/Paris', 'Europe/Berlin', 'Europe/Istanbul', 'Europe/Moscow',
  'America/New_York', 'America/Chicago', 'America/Denver', 'America/Los_Angeles',
  'America/Sao_Paulo', 'America/Toronto', 'Africa/Cairo', 'Africa/Nairobi',
  'Australia/Sydney', 'Australia/Melbourne', 'Pacific/Auckland',
];

/**
 * The complete IANA timezone list, sourced from the browser's own tz database
 * (so it always matches what the previews render) with a curated fallback.
 */
function getAllZones() {
  try {
    if (typeof Intl.supportedValuesOf === 'function') {
      const zones = Intl.supportedValuesOf('timeZone');
      if (Array.isArray(zones) && zones.length) {
        return zones.includes('UTC') ? zones : ['UTC', ...zones];
      }
    }
  } catch {
    /* fall through to curated list */
  }
  return FALLBACK_ZONES;
}

/**
 * Human-friendly "what time is it there right now" string for a zone, e.g.
 * "Fri, 02:15 PM GMT-5". Uses the browser's Intl data — the same source the
 * backend's ZoneId resolves against — so the super admin can sanity-check the
 * pick before saving. Returns null for an unparsable zone.
 */
export function zonePreview(tz) {
  if (!tz) return null;
  try {
    const now = new Date();
    const time = new Intl.DateTimeFormat(undefined, {
      timeZone: tz, weekday: 'short', hour: '2-digit', minute: '2-digit',
    }).format(now);
    let offset = '';
    try {
      offset = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'shortOffset' })
        .formatToParts(now)
        .find((p) => p.type === 'timeZoneName')?.value || '';
    } catch {
      /* shortOffset unsupported on this engine — omit the offset */
    }
    return offset ? `${time} ${offset}` : time;
  } catch {
    return null;
  }
}

/**
 * Searchable picker over every IANA timezone, with a live local-time preview.
 * Controlled component: `value` is an IANA zone id, `onChange(zoneId)` fires
 * on each edit. Server-side validation (ZoneId.of) remains the source of truth;
 * the inline hint is a UX convenience.
 */
export default function TimezonePicker({
  value, onChange, id = 'venue-timezone', required = false,
  disabled = false, disabledReason = '',
}) {
  const zones = useMemo(getAllZones, []);
  const listId = `${id}-datalist`;
  const known = useMemo(() => new Set(zones), [zones]);
  const isValid = !value || known.has(value);
  const preview = isValid ? zonePreview(value) : null;

  return (
    <>
      <input
        id={id}
        list={disabled ? undefined : listId}
        value={value || ''}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Search — e.g. America/Chicago, Asia/Kolkata, Europe/London"
        autoComplete="off"
        spellCheck={false}
        required={required}
        disabled={disabled}
        aria-invalid={!isValid}
        aria-disabled={disabled}
      />
      {!disabled && (
        <datalist id={listId}>
          {zones.map((z) => (
            <option key={z} value={z}>{z.replace(/_/g, ' ')}</option>
          ))}
        </datalist>
      )}
      <span style={{ fontSize: '0.78rem', color: disabled ? 'var(--warning, #b8860b)' : (isValid ? 'var(--text-muted)' : 'var(--danger, #e74c3c)') }}>
        {disabled
          ? `🔒 ${disabledReason || 'You don’t have permission to change this venue’s timezone.'}${preview ? ` (currently ${preview})` : ''}`
          : !isValid
            ? `"${value}" isn't a recognized IANA timezone — pick one from the list.`
            : preview
              ? `🕒 Current time there: ${preview}. All booking dates, schedules and check-in windows use this zone.`
              : 'All booking times and schedules are interpreted in this timezone. Choose the venue’s local zone.'}
      </span>
    </>
  );
}
