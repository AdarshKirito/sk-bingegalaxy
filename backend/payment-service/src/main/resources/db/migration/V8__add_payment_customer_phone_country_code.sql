-- V8: Add customer phone country code to payments table.
-- Mirrors V31 in booking-service.

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS customer_phone_country_code VARCHAR(8);

ALTER TABLE payments
    ALTER COLUMN customer_phone TYPE VARCHAR(20);

UPDATE payments
SET customer_phone_country_code = '+91'
WHERE customer_phone_country_code IS NULL
  AND customer_phone IS NOT NULL
  AND customer_phone ~ '^[6-9][0-9]{9}$';
