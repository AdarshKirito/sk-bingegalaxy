-- V68: per-binge currency.
-- A binge's currency is derived from its country (see CountryCurrency.java). Every price,
-- tax and payment for the binge is denominated in this currency — the customer never
-- chooses one. Existing binges default to the platform base (INR) and are backfilled from
-- their country where known.

ALTER TABLE binges ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL DEFAULT 'INR';

UPDATE binges SET currency = CASE UPPER(country)
    WHEN 'IN' THEN 'INR' WHEN 'US' THEN 'USD' WHEN 'GB' THEN 'GBP'
    WHEN 'CN' THEN 'CNY' WHEN 'JP' THEN 'JPY' WHEN 'AE' THEN 'AED'
    WHEN 'SG' THEN 'SGD' WHEN 'AU' THEN 'AUD' WHEN 'CA' THEN 'CAD'
    WHEN 'CH' THEN 'CHF' WHEN 'SA' THEN 'SAR' WHEN 'HK' THEN 'HKD'
    WHEN 'MY' THEN 'MYR' WHEN 'TH' THEN 'THB' WHEN 'ID' THEN 'IDR'
    WHEN 'PH' THEN 'PHP' WHEN 'VN' THEN 'VND' WHEN 'KR' THEN 'KRW'
    WHEN 'NZ' THEN 'NZD' WHEN 'ZA' THEN 'ZAR' WHEN 'BR' THEN 'BRL'
    WHEN 'MX' THEN 'MXN' WHEN 'RU' THEN 'RUB' WHEN 'TR' THEN 'TRY'
    WHEN 'QA' THEN 'QAR' WHEN 'KW' THEN 'KWD' WHEN 'BH' THEN 'BHD'
    WHEN 'OM' THEN 'OMR' WHEN 'LK' THEN 'LKR' WHEN 'NP' THEN 'NPR'
    WHEN 'BD' THEN 'BDT' WHEN 'PK' THEN 'PKR' WHEN 'EG' THEN 'EGP'
    WHEN 'NG' THEN 'NGN' WHEN 'KE' THEN 'KES' WHEN 'SE' THEN 'SEK'
    WHEN 'NO' THEN 'NOK' WHEN 'DK' THEN 'DKK' WHEN 'PL' THEN 'PLN'
    WHEN 'DE' THEN 'EUR' WHEN 'FR' THEN 'EUR' WHEN 'IT' THEN 'EUR'
    WHEN 'ES' THEN 'EUR' WHEN 'NL' THEN 'EUR' WHEN 'IE' THEN 'EUR'
    WHEN 'PT' THEN 'EUR' WHEN 'BE' THEN 'EUR' WHEN 'AT' THEN 'EUR'
    WHEN 'GR' THEN 'EUR' WHEN 'FI' THEN 'EUR' WHEN 'LU' THEN 'EUR'
    ELSE 'INR' END
WHERE country IS NOT NULL AND country <> '';
