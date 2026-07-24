-- Guest customer profiles (walk-in reservations without an account).
-- Admin creates a customer with "no email / not opting for email": only a name is
-- required, no credentials are issued or communicated, and the profile is flagged
-- so UIs can badge it and skip email-based flows.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS is_guest BOOLEAN NOT NULL DEFAULT FALSE;
