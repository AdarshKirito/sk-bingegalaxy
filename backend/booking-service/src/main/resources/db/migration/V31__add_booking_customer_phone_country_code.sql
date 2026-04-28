-- V31: Add customer phone country code to bookings.
-- Stored alongside national `customer_phone` to support international phones.
-- Backfill assumes legacy 10-digit Indian numbers (matches V8 in auth-service).

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS customer_phone_country_code VARCHAR(8);

-- Widen customer_phone to fit longer international numbers (was 15).
ALTER TABLE bookings
    ALTER COLUMN customer_phone TYPE VARCHAR(20);

UPDATE bookings
SET customer_phone_country_code = '+91'
WHERE customer_phone_country_code IS NULL
  AND customer_phone IS NOT NULL
  AND customer_phone ~ '^[6-9][0-9]{9}$';
