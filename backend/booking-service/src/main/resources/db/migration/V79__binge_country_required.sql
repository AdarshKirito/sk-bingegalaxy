-- V79 — Make binge.country mandatory.
--
-- WHY: country is load-bearing, not descriptive. It derives the venue's currency
-- (CountryCurrency), seeds its timezone, selects its tax rules, and now decides
-- which payment methods a customer is offered at checkout (PaymentMethodCatalog).
-- A NULL country silently means "INR / Asia-Kolkata / card-only", which is wrong
-- for every venue outside India — and made an existing Indian venue lose UPI once
-- payment methods started resolving from country.
--
-- Strategy: backfill from the venue's currency (always populated — it defaults to
-- INR and is re-derived whenever country changes), then enforce NOT NULL.

-- 1. Backfill from currency using the inverse of CountryCurrency.
--    Only the unambiguous 1:1 mappings are inverted here. EUR is deliberately
--    excluded: it maps to twelve countries, so guessing one would silently
--    misfile a venue's tax jurisdiction. Those rows fall through to step 2.
UPDATE binges
SET country = CASE UPPER(currency)
    WHEN 'INR' THEN 'IN'  WHEN 'USD' THEN 'US'  WHEN 'GBP' THEN 'GB'
    WHEN 'CNY' THEN 'CN'  WHEN 'JPY' THEN 'JP'  WHEN 'AED' THEN 'AE'
    WHEN 'SGD' THEN 'SG'  WHEN 'AUD' THEN 'AU'  WHEN 'CAD' THEN 'CA'
    WHEN 'CHF' THEN 'CH'  WHEN 'SAR' THEN 'SA'  WHEN 'HKD' THEN 'HK'
    WHEN 'MYR' THEN 'MY'  WHEN 'THB' THEN 'TH'  WHEN 'IDR' THEN 'ID'
    WHEN 'PHP' THEN 'PH'  WHEN 'VND' THEN 'VN'  WHEN 'KRW' THEN 'KR'
    WHEN 'NZD' THEN 'NZ'  WHEN 'ZAR' THEN 'ZA'  WHEN 'BRL' THEN 'BR'
    WHEN 'MXN' THEN 'MX'  WHEN 'RUB' THEN 'RU'  WHEN 'TRY' THEN 'TR'
    WHEN 'QAR' THEN 'QA'  WHEN 'KWD' THEN 'KW'  WHEN 'BHD' THEN 'BH'
    WHEN 'OMR' THEN 'OM'  WHEN 'LKR' THEN 'LK'  WHEN 'NPR' THEN 'NP'
    WHEN 'BDT' THEN 'BD'  WHEN 'PKR' THEN 'PK'  WHEN 'EGP' THEN 'EG'
    WHEN 'NGN' THEN 'NG'  WHEN 'KES' THEN 'KE'  WHEN 'SEK' THEN 'SE'
    WHEN 'NOK' THEN 'NO'  WHEN 'DKK' THEN 'DK'  WHEN 'PLN' THEN 'PL'
    ELSE NULL
  END
WHERE country IS NULL OR TRIM(country) = '';

-- 2. Anything still unresolved (NULL/blank currency, or the ambiguous EUR case)
--    falls back to the platform's historical default. These rows were already
--    behaving as Indian venues before this migration, so this preserves current
--    behaviour rather than changing it. Operators should review them:
--      SELECT id, name, currency FROM binges WHERE country = 'IN';
UPDATE binges
SET country = 'IN'
WHERE country IS NULL OR TRIM(country) = '';

-- 3. Normalise casing so the ISO-3166 alpha-2 contract holds everywhere.
UPDATE binges SET country = UPPER(TRIM(country));

-- 4. Enforce the invariant from here on.
ALTER TABLE binges ALTER COLUMN country SET NOT NULL;

-- 5. Guard against future bad writes at the storage layer, not just in bean
--    validation — a two-letter uppercase code is the whole contract.
ALTER TABLE binges DROP CONSTRAINT IF EXISTS chk_binges_country_iso2;
ALTER TABLE binges ADD CONSTRAINT chk_binges_country_iso2
    CHECK (country ~ '^[A-Z]{2}$');

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- allow:lock
-- Reviewed (destructive): `DROP CONSTRAINT IF EXISTS chk_binges_country_iso2` is
-- immediately followed by ADD CONSTRAINT — an idempotent replace, no data touched.
-- Reviewed (lock): `ALTER COLUMN country SET NOT NULL` takes a full scan under
-- ACCESS EXCLUSIVE. Accepted: the migration backfills every NULL first, and `binges`
-- is a small dimension table (venues, not bookings) where the scan is milliseconds.
