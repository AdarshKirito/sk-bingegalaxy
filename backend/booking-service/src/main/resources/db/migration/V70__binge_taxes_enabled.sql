-- Feature: per-binge tax system toggle (super-admin controlled).
-- TRUE keeps existing behaviour (tax engine runs); super-admin can switch a venue
-- to tax-free (e.g. jurisdictions where price must be tax-inclusive or exempt).
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS taxes_enabled BOOLEAN NOT NULL DEFAULT TRUE;
