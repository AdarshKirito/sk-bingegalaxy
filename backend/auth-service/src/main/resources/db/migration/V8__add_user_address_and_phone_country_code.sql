-- V8: Structured address + international phone support for users
-- Adds ISO-3166-1 alpha-2 country, region/state, city, postal code, address lines,
-- and a separate E.164 phone country-code dial prefix (e.g. "+91") so the existing
-- `phone` column can hold the national subscriber number only.
--
-- Backwards-compatibility:
--   - All new columns are NULLABLE so existing rows remain valid.
--   - `phone` length expanded from VARCHAR(15) to VARCHAR(20) to safely store
--     full E.164 numbers (max 15 digits) plus optional formatting characters
--     during write (server normalises to digits only).
--   - The existing `phone` UNIQUE constraint is preserved.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS address_line1 VARCHAR(200),
    ADD COLUMN IF NOT EXISTS address_line2 VARCHAR(200),
    ADD COLUMN IF NOT EXISTS city VARCHAR(100),
    ADD COLUMN IF NOT EXISTS state VARCHAR(100),
    ADD COLUMN IF NOT EXISTS country VARCHAR(2),
    ADD COLUMN IF NOT EXISTS postal_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS phone_country_code VARCHAR(8);

ALTER TABLE users ALTER COLUMN phone TYPE VARCHAR(20);

-- Default existing Indian-format rows to "+91" so historical data is consistent
-- without forcing a NOT NULL constraint (super-admin / Google-SSO users may
-- still have null phones until they complete their profile).
UPDATE users
SET phone_country_code = '+91'
WHERE phone IS NOT NULL
  AND phone_country_code IS NULL
  AND phone ~ '^[6-9][0-9]{9}$';

-- Helpful indexes for analytics / segmentation queries
CREATE INDEX IF NOT EXISTS idx_users_country ON users (country);
CREATE INDEX IF NOT EXISTS idx_users_postal_code ON users (postal_code);
