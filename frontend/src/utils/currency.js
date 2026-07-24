/**
 * Currency display helpers for the NATIVE per-binge currency model.
 *
 * Every binge prices/charges in its own currency (derived from its country). A price that
 * comes from the server is ALREADY in that binge's currency — there is no customer-side
 * conversion or currency switcher anymore. So formatting is just "render this number in
 * this ISO-4217 currency", which Intl does correctly per-currency (symbol + minor units:
 * ¥ has 0 decimals, ₹/$ have 2, etc.).
 */

const FALLBACK = 'INR';

/**
 * Format an amount that is already denominated in `currencyCode`.
 * @param {number|string} amount
 * @param {string} currencyCode ISO-4217 (e.g. "INR", "USD", "CNY"). Defaults to INR.
 * @param {{ narrow?: boolean, showCode?: boolean }} [opts]
 */
export function formatCurrency(amount, currencyCode = FALLBACK, opts = {}) {
  const n = Number(amount);
  const value = Number.isFinite(n) ? n : 0;
  const code = String(currencyCode || FALLBACK).toUpperCase();
  try {
    const out = new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: code,
      currencyDisplay: opts.narrow ? 'narrowSymbol' : 'symbol',
    }).format(value);
    return opts.showCode ? `${out} ${code}` : out;
  } catch {
    // Unknown/invalid code — fall back to a plain grouped number + code suffix.
    return `${value.toLocaleString()} ${code}`;
  }
}

/** The bare currency symbol for a code (e.g. "INR" → "₹", "CNY" → "CN¥"/"¥"). */
export function currencySymbol(currencyCode = FALLBACK) {
  const code = String(currencyCode || FALLBACK).toUpperCase();
  try {
    const parts = new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: code,
      currencyDisplay: 'narrowSymbol',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    }).formatToParts(0);
    return parts.find((p) => p.type === 'currency')?.value || code;
  } catch {
    return code;
  }
}

// ISO-3166 alpha-2 country → ISO-4217 currency. MUST mirror the backend CountryCurrency
// map so the admin form previews the same currency the server will derive on save.
const COUNTRY_CCY = {
  IN: 'INR', US: 'USD', GB: 'GBP', CN: 'CNY', JP: 'JPY', AE: 'AED', SG: 'SGD', AU: 'AUD',
  CA: 'CAD', CH: 'CHF', SA: 'SAR', HK: 'HKD', MY: 'MYR', TH: 'THB', ID: 'IDR', PH: 'PHP',
  VN: 'VND', KR: 'KRW', NZ: 'NZD', ZA: 'ZAR', BR: 'BRL', MX: 'MXN', RU: 'RUB', TR: 'TRY',
  QA: 'QAR', KW: 'KWD', BH: 'BHD', OM: 'OMR', LK: 'LKR', NP: 'NPR', BD: 'BDT', PK: 'PKR',
  EG: 'EGP', NG: 'NGN', KE: 'KES', SE: 'SEK', NO: 'NOK', DK: 'DKK', PL: 'PLN',
  DE: 'EUR', FR: 'EUR', IT: 'EUR', ES: 'EUR', NL: 'EUR', IE: 'EUR', PT: 'EUR', BE: 'EUR',
  AT: 'EUR', GR: 'EUR', FI: 'EUR', LU: 'EUR',
};

/** The currency a binge in this country will price/charge in (defaults to INR). */
export function currencyForCountry(countryIso2) {
  if (!countryIso2) return FALLBACK;
  return COUNTRY_CCY[String(countryIso2).trim().toUpperCase()] || FALLBACK;
}

/**
 * Resolve the currency for a binge-scoped object, tolerating the various shapes the API
 * returns (a binge, a booking, or a raw code). Falls back to INR so nothing renders blank.
 */
export function resolveCurrency(source) {
  if (!source) return FALLBACK;
  if (typeof source === 'string') return source.toUpperCase() || FALLBACK;
  return (
    source.currency ||
    source.paymentCurrencyCode ||
    source.bingeCurrency ||
    FALLBACK
  ).toUpperCase();
}
