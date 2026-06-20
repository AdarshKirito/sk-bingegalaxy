/**
 * Geo presentation helpers shared by the venue-discovery surfaces.
 *
 * Distance values arrive from the backend already computed (Haversine) and rounded
 * to one decimal kilometre. These helpers only format them for display — they never
 * recompute distance on the client, so what the customer sees matches what the
 * server ranked by.
 */

/**
 * Human-friendly distance label. Sub-kilometre distances render in metres so a venue
 * "300 m away" doesn't show as "0.3 km". Returns '' for missing/invalid input so
 * callers can conditionally render a badge.
 *
 * @param {number|null|undefined} km distance in kilometres
 */
export function formatDistance(km) {
  if (km == null || Number.isNaN(Number(km)) || !Number.isFinite(Number(km))) return '';
  const value = Number(km);
  if (value < 1) {
    const metres = Math.round(value * 1000);
    return `${metres} m away`;
  }
  // One decimal under 10 km (2.3 km), whole km beyond (12 km) — matches map-app UX.
  const rounded = value < 10 ? Math.round(value * 10) / 10 : Math.round(value);
  return `${rounded} km away`;
}

/** True when a binge carries usable coordinates (both lat and lng present, finite). */
export function hasCoordinates(binge) {
  const lat = Number(binge?.latitude);
  const lng = Number(binge?.longitude);
  return (
    binge?.latitude != null &&
    binge?.longitude != null &&
    Number.isFinite(lat) &&
    Number.isFinite(lng) &&
    lat >= -90 && lat <= 90 &&
    lng >= -180 && lng <= 180
  );
}
