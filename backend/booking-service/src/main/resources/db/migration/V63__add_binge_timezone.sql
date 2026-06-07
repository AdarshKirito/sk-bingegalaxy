-- V63: Add timezone column to binges table.
-- Each venue declares its own IANA timezone (e.g. 'Asia/Kolkata', 'America/New_York').
-- All booking-date validation, check-in window arithmetic, and tax-rule effective-date
-- windows are evaluated in this zone — never in the JVM default or a hardcoded constant.
-- Default 'Asia/Kolkata' preserves existing behaviour for all current venues.

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata';
