/**
 * Frontend mirror of booking-service's CountryTimezoneDefaults: derive the
 * venue's default IANA timezone from its structured address so the venue form
 * can pre-fill the TimezonePicker the moment the admin picks a country/state/
 * city — instead of a hardcoded Asia/Kolkata or the admin's browser zone.
 *
 * The backend derives the same default server-side when no explicit zone is
 * sent, so this is a UX convenience, not the authority. Keep the two maps in
 * sync when adding countries.
 */

const COUNTRY_ZONE = {
  IN: 'Asia/Kolkata', LK: 'Asia/Colombo', NP: 'Asia/Kathmandu', BD: 'Asia/Dhaka',
  PK: 'Asia/Karachi', AE: 'Asia/Dubai', SA: 'Asia/Riyadh', QA: 'Asia/Qatar',
  KW: 'Asia/Kuwait', BH: 'Asia/Bahrain', OM: 'Asia/Muscat', IL: 'Asia/Jerusalem',
  TR: 'Europe/Istanbul', SG: 'Asia/Singapore', MY: 'Asia/Kuala_Lumpur',
  TH: 'Asia/Bangkok', VN: 'Asia/Ho_Chi_Minh', PH: 'Asia/Manila',
  HK: 'Asia/Hong_Kong', TW: 'Asia/Taipei', CN: 'Asia/Shanghai', JP: 'Asia/Tokyo',
  KR: 'Asia/Seoul', GB: 'Europe/London', IE: 'Europe/Dublin', FR: 'Europe/Paris',
  DE: 'Europe/Berlin', NL: 'Europe/Amsterdam', BE: 'Europe/Brussels',
  LU: 'Europe/Luxembourg', CH: 'Europe/Zurich', AT: 'Europe/Vienna',
  IT: 'Europe/Rome', ES: 'Europe/Madrid', PT: 'Europe/Lisbon', PL: 'Europe/Warsaw',
  CZ: 'Europe/Prague', HU: 'Europe/Budapest', RO: 'Europe/Bucharest',
  GR: 'Europe/Athens', SE: 'Europe/Stockholm', NO: 'Europe/Oslo',
  DK: 'Europe/Copenhagen', FI: 'Europe/Helsinki', UA: 'Europe/Kyiv',
  EG: 'Africa/Cairo', MA: 'Africa/Casablanca', NG: 'Africa/Lagos',
  GH: 'Africa/Accra', KE: 'Africa/Nairobi', TZ: 'Africa/Dar_es_Salaam',
  ZA: 'Africa/Johannesburg', AR: 'America/Argentina/Buenos_Aires',
  CL: 'America/Santiago', CO: 'America/Bogota', PE: 'America/Lima',
  NZ: 'Pacific/Auckland',
};

const MULTI_ZONE_FALLBACK = {
  US: 'America/New_York', CA: 'America/Toronto', AU: 'Australia/Sydney',
  BR: 'America/Sao_Paulo', MX: 'America/Mexico_City', RU: 'Europe/Moscow',
  ID: 'Asia/Jakarta',
};

const STATE_ZONE = {
  // United States (split-zone states map to their dominant zone)
  'US|CT': 'America/New_York', 'US|DE': 'America/New_York', 'US|DC': 'America/New_York',
  'US|FL': 'America/New_York', 'US|GA': 'America/New_York', 'US|ME': 'America/New_York',
  'US|MD': 'America/New_York', 'US|MA': 'America/New_York', 'US|MI': 'America/Detroit',
  'US|NH': 'America/New_York', 'US|NJ': 'America/New_York', 'US|NY': 'America/New_York',
  'US|NC': 'America/New_York', 'US|OH': 'America/New_York', 'US|PA': 'America/New_York',
  'US|RI': 'America/New_York', 'US|SC': 'America/New_York', 'US|VT': 'America/New_York',
  'US|VA': 'America/New_York', 'US|WV': 'America/New_York', 'US|KY': 'America/New_York',
  'US|IN': 'America/Indiana/Indianapolis',
  'US|TN': 'America/Chicago', 'US|AL': 'America/Chicago', 'US|AR': 'America/Chicago',
  'US|IL': 'America/Chicago', 'US|IA': 'America/Chicago', 'US|KS': 'America/Chicago',
  'US|LA': 'America/Chicago', 'US|MN': 'America/Chicago', 'US|MS': 'America/Chicago',
  'US|MO': 'America/Chicago', 'US|NE': 'America/Chicago', 'US|ND': 'America/Chicago',
  'US|OK': 'America/Chicago', 'US|SD': 'America/Chicago', 'US|TX': 'America/Chicago',
  'US|WI': 'America/Chicago',
  'US|AZ': 'America/Phoenix', 'US|CO': 'America/Denver', 'US|ID': 'America/Boise',
  'US|MT': 'America/Denver', 'US|NM': 'America/Denver', 'US|UT': 'America/Denver',
  'US|WY': 'America/Denver',
  'US|CA': 'America/Los_Angeles', 'US|NV': 'America/Los_Angeles',
  'US|OR': 'America/Los_Angeles', 'US|WA': 'America/Los_Angeles',
  'US|AK': 'America/Anchorage', 'US|HI': 'Pacific/Honolulu',
  // Canada
  'CA|ON': 'America/Toronto', 'CA|QC': 'America/Toronto', 'CA|NS': 'America/Halifax',
  'CA|NB': 'America/Halifax', 'CA|PE': 'America/Halifax', 'CA|NL': 'America/St_Johns',
  'CA|MB': 'America/Winnipeg', 'CA|SK': 'America/Regina', 'CA|AB': 'America/Edmonton',
  'CA|BC': 'America/Vancouver',
  // Australia
  'AU|NSW': 'Australia/Sydney', 'AU|VIC': 'Australia/Melbourne',
  'AU|QLD': 'Australia/Brisbane', 'AU|SA': 'Australia/Adelaide',
  'AU|WA': 'Australia/Perth', 'AU|TAS': 'Australia/Hobart',
  'AU|NT': 'Australia/Darwin', 'AU|ACT': 'Australia/Sydney',
  // Brazil / Indonesia dominant zones
  'BR|SP': 'America/Sao_Paulo', 'BR|RJ': 'America/Sao_Paulo', 'BR|AM': 'America/Manaus',
  'ID|JK': 'Asia/Jakarta', 'ID|BA': 'Asia/Makassar', 'ID|PA': 'Asia/Jayapura',
};

const STATE_NAME_TO_CODE = {
  'US|NEW YORK': 'NY', 'US|CALIFORNIA': 'CA', 'US|TEXAS': 'TX', 'US|FLORIDA': 'FL',
  'US|ILLINOIS': 'IL', 'US|WASHINGTON': 'WA', 'US|NEVADA': 'NV', 'US|ARIZONA': 'AZ',
  'US|COLORADO': 'CO', 'US|GEORGIA': 'GA', 'US|MASSACHUSETTS': 'MA',
  'US|NEW JERSEY': 'NJ', 'US|PENNSYLVANIA': 'PA', 'US|MICHIGAN': 'MI',
  'US|OHIO': 'OH', 'US|OREGON': 'OR',
  'CA|ONTARIO': 'ON', 'CA|QUEBEC': 'QC', 'CA|BRITISH COLUMBIA': 'BC',
  'CA|ALBERTA': 'AB', 'CA|MANITOBA': 'MB', 'CA|SASKATCHEWAN': 'SK',
  'CA|NOVA SCOTIA': 'NS',
  'AU|NEW SOUTH WALES': 'NSW', 'AU|VICTORIA': 'VIC', 'AU|QUEENSLAND': 'QLD',
  'AU|SOUTH AUSTRALIA': 'SA', 'AU|WESTERN AUSTRALIA': 'WA', 'AU|TASMANIA': 'TAS',
  'AU|NORTHERN TERRITORY': 'NT',
};

/**
 * Best-effort IANA zone for a structured address; null when unknown.
 * @param {{country?: string, state?: string, city?: string}} address
 */
export function guessTimezone(address = {}) {
  const cc = (address.country || '').trim().toUpperCase();
  if (!cc) return null;
  if (MULTI_ZONE_FALLBACK[cc]) {
    const st = (address.state || '').trim().replace(/\s+/g, ' ').toUpperCase();
    if (st) {
      const code = STATE_NAME_TO_CODE[`${cc}|${st}`] || st;
      const zone = STATE_ZONE[`${cc}|${code}`];
      if (zone) return zone;
    }
    return MULTI_ZONE_FALLBACK[cc];
  }
  return COUNTRY_ZONE[cc] || null;
}

export default guessTimezone;
