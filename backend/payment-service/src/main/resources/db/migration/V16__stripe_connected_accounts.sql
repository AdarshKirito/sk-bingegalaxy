-- V16 — Stripe Connect: map each venue to its own connected account.
--
-- WHY this table lives in payment-service and not as a column on booking-service's
-- `binges`: the connected account is a payment concern (KYC state, charge/payout
-- capability) and adding it to the Binge aggregate would couple booking-service to
-- a gateway it otherwise knows nothing about. Payment-service already resolves a
-- venue through the internal binge snapshot, so binge_id is a stable foreign key
-- across the service boundary without a shared schema.
--
-- MODEL: direct charges on the connected account. The charge happens on the
-- VENUE's account, in the venue's country — which is what makes local payment
-- methods (UPI for an Indian venue) available to a customer anywhere in the world.
-- The platform's cut rides along as application_fee_amount.

CREATE TABLE IF NOT EXISTS payment_connected_accounts (
    id                  BIGSERIAL PRIMARY KEY,

    -- One connected account per venue. UNIQUE is the real guard against the
    -- double-onboarding race: duplicate Stripe accounts each need their own KYC
    -- and are painful to unwind, so the DB refuses the second one outright.
    binge_id            BIGINT       NOT NULL,

    provider            VARCHAR(32)  NOT NULL DEFAULT 'stripe',

    -- Stripe's acct_… identifier.
    account_id          VARCHAR(128) NOT NULL,

    -- The account's country, fixed by Stripe at creation. Kept here so we can
    -- resolve payment methods without a round trip, and detect drift if a venue's
    -- country is later edited (which would require re-onboarding, not an update).
    -- VARCHAR(2), not CHAR(2): Hibernate runs with ddl-auto=validate and maps
    -- @Column(length = 2) on a String to VARCHAR. A CHAR column is reported as
    -- bpchar and fails validation, so the service would refuse to start. This also
    -- matches the convention used everywhere else (e.g. binges.country).
    country             VARCHAR(2)   NOT NULL,

    -- Capability flags mirrored from Stripe (refreshed on webhook + status poll).
    -- charges_enabled is the one that gates checkout: an account mid-KYC exists
    -- but cannot take money, and offering it would fail at the last step.
    charges_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    payouts_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    details_submitted   BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_connected_account_binge  UNIQUE (binge_id),
    CONSTRAINT uq_connected_account_acct   UNIQUE (account_id),
    CONSTRAINT chk_connected_account_country CHECK (country ~ '^[A-Z]{2}$')
);

-- Webhooks arrive keyed by account id, so that lookup must be indexed. (The
-- UNIQUE constraints above already provide indexes for binge_id and account_id;
-- this partial index serves the "which venues can actually charge?" admin query.)
CREATE INDEX IF NOT EXISTS idx_connected_accounts_chargeable
    ON payment_connected_accounts (binge_id)
    WHERE charges_enabled = TRUE;

COMMENT ON TABLE payment_connected_accounts IS
    'Stripe Connect account per venue; direct-charge model so venue-country payment rails apply.';
