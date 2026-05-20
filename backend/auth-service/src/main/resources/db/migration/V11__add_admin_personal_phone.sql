-- V11: Personal phone for admin/super-admin contact (super-admin only visibility)
--
-- Adds an OPTIONAL secondary phone number used by super-admins to reach an
-- admin off-hours / out-of-band. Distinct from the existing `phone` column
-- (which is the admin's primary public contact and remains uniquely indexed).
-- The personal phone is intentionally NOT uniquely indexed — two admins may
-- legitimately share the same emergency contact (rare, but possible) and we
-- never expose it to customers.
--
-- Backwards-compatibility:
--   - Both new columns are NULLABLE so existing rows remain valid.
--   - No backfill — admins fill this in themselves or super-admin records it
--     when creating / editing the admin.
--   - Customer-facing UserDto consumers do not surface this column, but the
--     application layer is responsible for that — DB just stores the value.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS personal_phone VARCHAR(20),
    ADD COLUMN IF NOT EXISTS personal_phone_country_code VARCHAR(8);
