-- V78: Split binge contact into PUBLIC (customer-facing support_*) and
-- PERSONAL/OWNER (super-admin -> venue-admin escalation) channels, plus
-- "this phone is also WhatsApp" flags so venues don't have to re-type the
-- same number to light up the WhatsApp channel.
--
-- owner_* fields are INTERNAL: they must never be exposed through the
-- public binge DTOs (see BingeService.toPublicDto).

ALTER TABLE binges ADD COLUMN IF NOT EXISTS owner_email VARCHAR(150);
ALTER TABLE binges ADD COLUMN IF NOT EXISTS owner_phone VARCHAR(20);
ALTER TABLE binges ADD COLUMN IF NOT EXISTS owner_phone_country_code VARCHAR(8);
ALTER TABLE binges ADD COLUMN IF NOT EXISTS owner_phone_is_whatsapp BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE binges ADD COLUMN IF NOT EXISTS support_phone_is_whatsapp BOOLEAN NOT NULL DEFAULT FALSE;
