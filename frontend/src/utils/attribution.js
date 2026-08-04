/**
 * Marketing attribution capture (distribution design G-B).
 *
 * Google Things to Do is a product feed plus a deep link — Google never takes the
 * booking, the traveller checks out here. So the ONLY evidence that Google produced a
 * booking is a parameter on the landing URL. Capture it on arrival, carry it to
 * checkout, and the channel becomes measurable; miss it and the business case for
 * building the channel at all is unprovable.
 *
 * Two deliberate choices:
 *
 * - **sessionStorage, not a cookie.** First-party and session-scoped, so it needs no
 *   consent banner and never travels to a third party. It is also automatically
 *   discarded when the tab closes, which is the correct lifetime for something that
 *   describes "how this visit started".
 *
 * - **Last non-direct touch wins.** A visitor who arrives via Google, wanders off, and
 *   returns by typing the URL should still be credited to Google — a direct return is
 *   not a competing claim. But a later click from a *different* campaign is, and
 *   overwrites it.
 *
 * Attribution is a reporting dimension. It never changes price, availability or what a
 * customer is allowed to book — the server enforces that independently, because these
 * values come from a URL the customer controls.
 */

const STORAGE_KEY = 'skbg.attribution';

/** Must match BookingAttribution.WINDOW on the server. */
export const ATTRIBUTION_WINDOW_DAYS = 30;

/** Column widths in booking_db; truncate here so the server never has to reject. */
const MAX_SOURCE = 64;
const MAX_REF = 128;

/**
 * Query parameters we read, in priority order. `utm_source` is the convention Google
 * Things to Do and every other referrer already emit, so nothing bespoke is required
 * of the channel.
 */
const SOURCE_PARAMS = ['utm_source', 'sk_source'];
const REF_PARAMS = ['sk_click', 'utm_campaign', 'utm_id'];

const canonicalSource = (raw) => {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim().toLowerCase();
  if (!trimmed) return null;
  return trimmed.slice(0, MAX_SOURCE);
};

const trimRef = (raw) => {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  return trimmed ? trimmed.slice(0, MAX_REF) : null;
};

const firstPresent = (params, keys) => {
  for (const key of keys) {
    const value = params.get(key);
    if (value && value.trim()) return value;
  }
  return null;
};

const isExpired = (capturedAt, now = Date.now()) => {
  const t = Date.parse(capturedAt);
  if (Number.isNaN(t)) return true;
  // A future timestamp fails closed rather than lasting forever — the clock is the
  // visitor's own and a skewed one should not hold attribution open indefinitely.
  if (t > now) return true;
  return now - t > ATTRIBUTION_WINDOW_DAYS * 24 * 60 * 60 * 1000;
};

const readStored = () => {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed?.source || isExpired(parsed.capturedAt)) return null;
    return parsed;
  } catch {
    // Private browsing, disabled storage, or a corrupt value. Attribution is
    // analytics: it must never be able to break the page it is measuring.
    return null;
  }
};

/**
 * Read attribution parameters off the current URL and remember them.
 *
 * Call once on app start. Safe to call repeatedly — a visit with no attribution
 * parameters leaves any existing capture untouched, which is what makes "last
 * NON-DIRECT touch wins" work.
 *
 * @param {string} [search] - defaults to the live query string; injectable for tests.
 * @returns {{source: string, ref: string|null, capturedAt: string}|null}
 */
export function captureAttribution(search) {
  if (typeof window === 'undefined') return null;
  try {
    const params = new URLSearchParams(
      search ?? window.location?.search ?? ''
    );
    const source = canonicalSource(firstPresent(params, SOURCE_PARAMS));

    // No source on this URL: a direct visit does not overwrite an earlier referral.
    if (!source) return readStored();

    const captured = {
      source,
      ref: trimRef(firstPresent(params, REF_PARAMS)),
      capturedAt: new Date().toISOString(),
    };
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(captured));
    return captured;
  } catch {
    return null;
  }
}

/** Current attribution, or null. Expired entries read as absent. */
export function getAttribution() {
  return readStored();
}

/**
 * The three fields to merge into a POST /bookings body. Returns an empty object when
 * there is nothing to report, so callers can spread it unconditionally without
 * sending nulls the server would only have to ignore.
 */
export function attributionPayload() {
  const a = readStored();
  if (!a) return {};
  return {
    attributionSource: a.source,
    ...(a.ref ? { attributionRef: a.ref } : {}),
    attributionCapturedAt: a.capturedAt,
  };
}

/** Clear the capture. Exposed for tests and for an explicit "forget me" action. */
export function clearAttribution() {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* nothing to clear if storage is unavailable */
  }
}
