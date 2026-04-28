-- V29: Structured address + international phone country codes for binges
-- Mirrors V8 in auth-service (users table). Allows binge venue addresses to
-- be captured as discrete fields (street/city/state/country/postal) rather
-- than a single free-form blob, enabling: zip-code based search, geo
-- analytics, integration with map APIs, and clean export to invoices.
--
-- All new columns are NULLABLE. Existing rows keep their legacy `address`
-- value untouched; the application layer treats `address` as the
-- human-readable composed display when fields are missing.

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS address_line1 VARCHAR(200),
    ADD COLUMN IF NOT EXISTS address_line2 VARCHAR(200),
    ADD COLUMN IF NOT EXISTS city VARCHAR(100),
    ADD COLUMN IF NOT EXISTS state VARCHAR(100),
    ADD COLUMN IF NOT EXISTS country VARCHAR(2),
    ADD COLUMN IF NOT EXISTS postal_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS support_phone_country_code VARCHAR(8),
    ADD COLUMN IF NOT EXISTS support_whatsapp_country_code VARCHAR(8);

ALTER TABLE binges ALTER COLUMN support_phone TYPE VARCHAR(20);
ALTER TABLE binges ALTER COLUMN support_whatsapp TYPE VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_binges_country ON binges (country);
CREATE INDEX IF NOT EXISTS idx_binges_postal_code ON binges (postal_code);
