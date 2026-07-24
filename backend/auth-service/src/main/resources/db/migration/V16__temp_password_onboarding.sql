-- V16: Temporary-password onboarding for admin-created customers.
--
-- When a front-desk admin creates a customer to take a reservation, the admin
-- must NOT end up knowing a long-lived password for that customer (insider risk,
-- no non-repudiation). Instead the account is provisioned with a *temporary*
-- password that is emailed + texted to the customer and that:
--   * forces a password change on login (must_change_password = TRUE), and
--   * may only be used for a small number of logins (temp_password_logins_remaining)
--     before it is rejected and the customer must use "Forgot password".
--
-- This mirrors AWS IAM initial console passwords and Active Directory's
-- "user must change password at next logon".
--
-- Existing accounts are normal accounts: must_change_password defaults to FALSE
-- and temp_password_logins_remaining stays NULL (no temp-password lifecycle).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS must_change_password           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS temp_password_logins_remaining INT;

COMMENT ON COLUMN users.must_change_password IS
    'TRUE while the account still holds an admin-issued temporary password and must change it.';
COMMENT ON COLUMN users.temp_password_logins_remaining IS
    'Remaining logins allowed on the temporary password; NULL for normal accounts. Reaching 0 forces Forgot-password.';
