-- Verified email change: the OTP token can now carry the address being switched TO.
-- Flow: request-change (password re-auth) -> 6-digit code sent to the NEW address ->
-- confirm with the code -> email switches and is immediately verified.
ALTER TABLE email_verification_token
    ADD COLUMN IF NOT EXISTS pending_email VARCHAR(150);
