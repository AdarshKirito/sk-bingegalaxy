-- V32: Widen waitlist customer_phone and add international phone country code
-- so waitlist entries (customer-facing notifications) can dispatch SMS/WhatsApp
-- to non-Indian numbers via Twilio E.164.

ALTER TABLE waitlist_entries
    ALTER COLUMN customer_phone TYPE VARCHAR(20);

ALTER TABLE waitlist_entries
    ADD COLUMN IF NOT EXISTS customer_phone_country_code VARCHAR(8);

-- Backfill: assume legacy Indian numbers (10-digit) had +91 prefix.
UPDATE waitlist_entries
SET customer_phone_country_code = '+91'
WHERE customer_phone_country_code IS NULL
  AND customer_phone IS NOT NULL
  AND length(customer_phone) = 10;
