-- V3 — the INBOUND credential, which had nowhere to live.
--
-- THE CONFLATION ------------------------------------------------------------
-- connections.credential_ref is a pointer to the secret SK Binge presents TO a
-- provider. That is the OUTBOUND direction, and it is the only one V1 modelled.
--
-- OCTO inverts it. SK Binge HOSTS the endpoint, and each reseller connects with a key
-- SK Binge issues for that reseller↔venue pair. That is a different secret, in the
-- opposite direction, with a different lifecycle — and ResellerAuthenticator was
-- reading credential_ref to check it.
--
-- The consequence was total for the only provider anyone can currently use. SIMULATOR
-- is PLATFORM_MANAGED, so ConnectionService.create REFUSES a credential reference and
-- credential_ref is NULL by construction. ResellerAuthenticator skips any connection
-- with a null ref. So no reseller could ever authenticate against the simulator: the
-- OCTO surface was unreachable for every connection that could actually exist, and the
-- 401 it returned was indistinguishable from a wrong key.
--
-- WHY A HASH AND NOT A REFERENCE -------------------------------------------
-- An outbound secret must be recoverable — we have to present it. An inbound one must
-- not be: we only ever verify it. Storing the hash means a dump of distribution_db
-- yields nothing an attacker can present, which is not true of a reference that
-- resolves to a live secret. The key is shown to the operator exactly once, at issue.
--
-- It also fixes the lookup. ResellerAuthenticator scanned EVERY active connection and
-- compared secrets one by one; with a unique index on the digest it is a single
-- indexed read, so the cost no longer grows with the number of venues on the platform.

ALTER TABLE connections
    ADD COLUMN IF NOT EXISTS reseller_key_hash      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reseller_key_hint      VARCHAR(40),
    ADD COLUMN IF NOT EXISTS reseller_key_issued_at TIMESTAMP;

-- Unique so one key can never resolve to two connections — which would make the
-- venue a reservation lands against depend on row order.
-- Partial: every connection that has not been issued a key holds NULL, and NULLs are
-- distinct in a plain unique index but the partial form states the intent.
CREATE UNIQUE INDEX IF NOT EXISTS uk_connection_reseller_key
    ON connections (reseller_key_hash)
    WHERE reseller_key_hash IS NOT NULL;

COMMENT ON COLUMN connections.reseller_key_hash IS
    'SHA-256 (hex) of the key SK Binge issued to the reseller for this connection. The key itself is shown once at issue and never stored — this column can verify a presented key and cannot reproduce one. Distinct from credential_ref, which points at the secret we present TO a provider. See V3.';
COMMENT ON COLUMN connections.reseller_key_hint IS
    'Masked tail of the issued key, e.g. ****a1b2, so an operator can tell two keys apart in the console without either being recoverable.';
