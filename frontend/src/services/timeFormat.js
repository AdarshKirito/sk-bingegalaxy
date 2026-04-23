// ════════════════════════════════════════════════════════════════════
//  Centralized timestamp formatting for the admin + customer UIs.
//
//  The backend stores timestamps as Java `LocalDateTime` (no timezone)
//  but the JVM persists them in UTC. When Jackson serializes them to
//  JSON the "Z" suffix is missing, so `new Date(raw)` would incorrectly
//  interpret the value as local time — causing an offset equal to the
//  browser's UTC offset (e.g. -05:30 in India).
//
//  `parseServerDate` normalizes the server timestamp to an accurate
//  Date object in the browser's local timezone (which is the admin /
//  customer "dashboard current time").
// ════════════════════════════════════════════════════════════════════

/**
 * Parse a server timestamp (possibly naive ISO without TZ) as UTC.
 * Returns null if input is falsy or unparsable.
 */
export function parseServerDate(value) {
  if (!value) return null;
  if (value instanceof Date) return isNaN(value.getTime()) ? null : value;

  // If already a number (epoch ms) just wrap it.
  if (typeof value === 'number') {
    const d = new Date(value);
    return isNaN(d.getTime()) ? null : d;
  }

  if (typeof value !== 'string') return null;
  let str = value.trim();
  if (!str) return null;

  // If the string already has timezone info (Z, +HH:MM, -HH:MM at the end)
  // hand it directly to the Date parser.
  const hasTz = /(Z|[+\-]\d{2}:?\d{2})$/.test(str);
  if (!hasTz) {
    // Naive timestamp → treat as UTC.
    // Normalize to "YYYY-MM-DDTHH:mm:ss.sssZ" form.
    if (str.includes(' ') && !str.includes('T')) str = str.replace(' ', 'T');
    str += 'Z';
  }

  const d = new Date(str);
  return isNaN(d.getTime()) ? null : d;
}

const DEFAULT_OPTS = {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
};

/**
 * Format a server timestamp in the browser's local timezone.
 * Example → "21 Apr 2026, 10:15 AM".
 */
export function formatServerDateTime(value, options) {
  const d = parseServerDate(value);
  if (!d) return '—';
  return d.toLocaleString(undefined, { ...DEFAULT_OPTS, ...(options || {}) });
}

/**
 * Format a server timestamp as a relative string ("just now", "3 min ago", "yesterday").
 * Falls back to an absolute date if the event is more than a week old.
 */
export function formatRelativeTime(value, now = new Date()) {
  const d = parseServerDate(value);
  if (!d) return '';
  const diffSec = Math.round((now.getTime() - d.getTime()) / 1000);

  if (Math.abs(diffSec) < 10) return 'just now';
  if (diffSec < 60) return `${diffSec}s ago`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)} min ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} hr ago`;
  if (diffSec < 172800) return 'yesterday';
  if (diffSec < 604800) return `${Math.floor(diffSec / 86400)} days ago`;

  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

/**
 * Combined absolute + relative display — mirrors how real-world
 * SaaS dashboards (Stripe, Linear, GitHub) show audit log entries.
 * Example → { absolute: "21 Apr 2026, 10:15 AM", relative: "3 min ago" }
 */
export function formatServerTimestamp(value, options) {
  return {
    absolute: formatServerDateTime(value, options),
    relative: formatRelativeTime(value),
    iso: parseServerDate(value)?.toISOString() || '',
  };
}
