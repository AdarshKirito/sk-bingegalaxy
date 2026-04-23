-- =============================================================================
-- V21 — Loyalty Program v2 (Bonvoy / Sephora / Delta-class)
--
--   Introduces a tenant-aware, effective-dated, event-sourced loyalty model
--   with separated currencies (redeemable Points + Qualification Credits +
--   Lifetime Credits), FIFO points lots, platform + per-binge rule layers,
--   a perk catalog, and immutable ledger + membership event log.
--
--   ── Design invariants ──────────────────────────────────────────────────
--   1. Append-only ledger. Corrections are new rows, never UPDATEs.
--   2. Tier state is materialized on `loyalty_membership` and recalculated on
--      earn / expire / adjust / status-match — never on read.
--   3. `tenant_id` columns are present on every table now (nullable).  When
--      the platform goes multi-tenant, enabling Postgres RLS is additive,
--      non-breaking.
--   4. Effective-dating (`effective_from` / `effective_to`) on every rule
--      prevents retroactive devaluation (Delta-style) and lets super-admins
--      schedule rate changes with ≥90-day notice.
--   5. Legacy binges (those that exist before this program rolls out) are
--      auto-enrolled with `status = ENABLED_LEGACY` and `legacy_frozen = TRUE`
--      so their current behavior is preserved exactly.  The V22 backfill
--      performs that enrollment; this file ships pure DDL + platform seed.
--   6. `loyalty_points_lot` FIFO replaces the aggregate-balance expiry model
--      so partial expirations and per-binge point scoping both become
--      natural operations.
--   7. All identifiers are BIGSERIAL; all money is DECIMAL(12,2) (matches
--      existing bookings schema); all timestamps are TIMESTAMP WITHOUT TZ
--      stored in UTC (matches existing convention — see V17 / V19).
-- =============================================================================

-- ═══════════════════════════════════════════════════════════════════════════
--   1. PLATFORM LAYER   (super-admin owned, global configuration)
-- ═══════════════════════════════════════════════════════════════════════════

-- One row per program.  Seeded with the single default program.  Future
-- multi-program support (e.g. white-label sub-programs per tenant) is a
-- matter of inserting more rows — no schema change needed.
CREATE TABLE loyalty_program (
    id                         BIGSERIAL    PRIMARY KEY,
    tenant_id                  BIGINT,
    code                       VARCHAR(40)  NOT NULL,
    display_name               VARCHAR(120) NOT NULL,
    description                VARCHAR(500),
    active                     BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Global behavior toggles.  Stored as discrete columns (not a JSON
    -- blob) so they are indexable, auditable, and trivially editable via
    -- the super-admin UI.
    silent_enrollment_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    guest_shadow_enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    retroactive_credit_days    INTEGER      NOT NULL DEFAULT 30,
    points_expiry_days         INTEGER      NOT NULL DEFAULT 540,  -- 18 months
    devaluation_notice_days    INTEGER      NOT NULL DEFAULT 90,
    status_match_enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    status_challenge_days      INTEGER      NOT NULL DEFAULT 90,
    welcome_bonus_points       BIGINT       NOT NULL DEFAULT 500,
    birthday_bonus_points      BIGINT       NOT NULL DEFAULT 250,
    allow_negative_balance     BOOLEAN      NOT NULL DEFAULT FALSE,
    gifting_enabled            BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at                 TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_loyalty_program_code UNIQUE (tenant_id, code)
);


-- Effective-dated tier definitions.  Super-admin edits create a NEW row
-- with a new `effective_from` rather than mutating the old one — so past
-- tier computations remain reproducible from the ledger alone.
CREATE TABLE loyalty_tier_definition (
    id                               BIGSERIAL    PRIMARY KEY,
    tenant_id                        BIGINT,
    program_id                       BIGINT       NOT NULL REFERENCES loyalty_program(id),
    code                             VARCHAR(30)  NOT NULL,
    display_name                     VARCHAR(60)  NOT NULL,
    rank_order                       INTEGER      NOT NULL,

    qualification_credits_required   BIGINT       NOT NULL DEFAULT 0,
    qualification_window_days        INTEGER      NOT NULL DEFAULT 365,
    lifetime_credits_required        BIGINT,                                -- nullable (lifetime-only tiers)
    lifetime_years_held_required     INTEGER,                               -- nullable

    -- How long the tier is held after qualifying.  NULL = permanent
    -- (Bronze and Lifetime Platinum).  Positive value = "through end of
    -- calendar year N years later" (Bonvoy model).
    validity_calendar_years_after    INTEGER,
    soft_landing_tier_code           VARCHAR(30),

    color_hex                        VARCHAR(9),
    icon_key                         VARCHAR(40),

    effective_from                   TIMESTAMP    NOT NULL DEFAULT NOW(),
    effective_to                     TIMESTAMP,
    created_at                       TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by_admin_id              BIGINT,

    CONSTRAINT uq_tier_effective UNIQUE (program_id, code, effective_from)
);

CREATE INDEX idx_tier_def_active
    ON loyalty_tier_definition (program_id, rank_order)
    WHERE effective_to IS NULL;


-- The library of perks the platform knows how to deliver.  Each row
-- references a named strategy handler in code (see PerkDeliveryHandler
-- interface).  Adding a new perk = insert a catalog row + drop in a
-- @PerkHandler("KEY") Spring bean.  Open-closed.
CREATE TABLE loyalty_perk_catalog (
    id                      BIGSERIAL    PRIMARY KEY,
    tenant_id               BIGINT,
    program_id              BIGINT       NOT NULL REFERENCES loyalty_program(id),
    code                    VARCHAR(60)  NOT NULL,
    display_name            VARCHAR(120) NOT NULL,
    description             VARCHAR(500),
    category                VARCHAR(20)  NOT NULL,  -- FINANCIAL / SOFT / INVISIBLE
    fulfillment_type        VARCHAR(20)  NOT NULL,  -- AUTOMATIC / MANUAL / ON_DEMAND
    delivery_handler_key    VARCHAR(80)  NOT NULL,

    default_point_cost      BIGINT       NOT NULL DEFAULT 0,  -- 0 = tier entitlement, >0 = claimable
    cooldown_hours          INTEGER      NOT NULL DEFAULT 0,
    params_json             TEXT,                              -- handler-specific params

    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    effective_from          TIMESTAMP    NOT NULL DEFAULT NOW(),
    effective_to            TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_perk_code UNIQUE (program_id, code)
);


-- Which perks each tier gets by default.  `auto_grant` distinguishes
-- silent entitlements (applied to every eligible booking) from catalog
-- items the member must explicitly claim.
CREATE TABLE loyalty_tier_perk (
    id                      BIGSERIAL    PRIMARY KEY,
    tenant_id               BIGINT,
    tier_definition_id      BIGINT       NOT NULL REFERENCES loyalty_tier_definition(id),
    perk_id                 BIGINT       NOT NULL REFERENCES loyalty_perk_catalog(id),
    override_point_cost     BIGINT,
    auto_grant              BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order              INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_tier_perk UNIQUE (tier_definition_id, perk_id)
);


-- ═══════════════════════════════════════════════════════════════════════════
--   2. TENANT (BINGE) LAYER   (admin-configurable, per-binge opt-in)
-- ═══════════════════════════════════════════════════════════════════════════

-- Opt-in registry.  A row exists only for binges that have interacted with
-- the program at least once (either opted in, opted out explicitly, or
-- were grandfathered by the V22 backfill).  Absence = new binge that has
-- not yet been evaluated.
CREATE TABLE loyalty_binge_binding (
    id                       BIGSERIAL    PRIMARY KEY,
    tenant_id                BIGINT,
    program_id               BIGINT       NOT NULL REFERENCES loyalty_program(id),
    binge_id                 BIGINT       NOT NULL,

    -- ENABLED         = opted in by admin, rules active
    -- DISABLED        = opted out, no earn / burn on this binge
    -- ENABLED_LEGACY  = grandfathered from pre-v2, rules frozen, customer
    --                   behavior identical to pre-migration state
    status                   VARCHAR(20)  NOT NULL,
    legacy_frozen            BOOLEAN      NOT NULL DEFAULT FALSE,

    enrolled_at              TIMESTAMP,
    enrolled_by_admin_id     BIGINT,
    disabled_at              TIMESTAMP,
    disabled_by_admin_id     BIGINT,

    effective_from           TIMESTAMP    NOT NULL DEFAULT NOW(),
    effective_to             TIMESTAMP,

    created_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    version                  BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_binding_binge_program UNIQUE (program_id, binge_id)
);

CREATE INDEX idx_binding_enabled
    ON loyalty_binge_binding (binge_id, status)
    WHERE status IN ('ENABLED', 'ENABLED_LEGACY');


-- Per-binge earn rules.  Multiple rows allowed (tier-specific, time-boxed
-- promos, etc.).  Resolver picks the most specific active row at earn time.
CREATE TABLE loyalty_binge_earning_rule (
    id                       BIGSERIAL      PRIMARY KEY,
    tenant_id                BIGINT,
    binding_id               BIGINT         NOT NULL REFERENCES loyalty_binge_binding(id),
    tier_code                VARCHAR(30),                      -- NULL = all tiers

    rule_type                VARCHAR(30)    NOT NULL DEFAULT 'FLAT_PER_AMOUNT',
    points_numerator         BIGINT         NOT NULL,          -- e.g. 10
    amount_denominator       DECIMAL(12,2)  NOT NULL,          -- e.g. 1.00  → 10 pts / ₹1
    tier_multiplier          DECIMAL(5,2)   NOT NULL DEFAULT 1.00,
    qc_multiplier            DECIMAL(5,2)   NOT NULL DEFAULT 1.00,
    min_booking_amount       DECIMAL(12,2),
    cap_per_booking          BIGINT,
    daily_velocity_cap       BIGINT,                           -- anti-farm

    effective_from           TIMESTAMP      NOT NULL DEFAULT NOW(),
    effective_to             TIMESTAMP,
    created_at               TIMESTAMP      NOT NULL DEFAULT NOW(),
    created_by_admin_id      BIGINT
);

CREATE INDEX idx_earn_rule_binding_active
    ON loyalty_binge_earning_rule (binding_id, effective_from)
    WHERE effective_to IS NULL;


-- Per-binge redemption (burn) rules.  Single active row per binding in
-- the common case, but effective-dated so rate changes are trackable.
CREATE TABLE loyalty_binge_redemption_rule (
    id                              BIGSERIAL      PRIMARY KEY,
    tenant_id                       BIGINT,
    binding_id                      BIGINT         NOT NULL REFERENCES loyalty_binge_binding(id),
    points_per_currency_unit        BIGINT         NOT NULL,    -- e.g. 100 pts = ₹1
    min_redemption_points           BIGINT         NOT NULL DEFAULT 0,
    max_redemption_percent          DECIMAL(5,2)   NOT NULL DEFAULT 100.00,
    tier_bonus_pct_json             TEXT,                       -- {"GOLD":5,"PLATINUM":10}

    effective_from                  TIMESTAMP      NOT NULL DEFAULT NOW(),
    effective_to                    TIMESTAMP,
    created_at                      TIMESTAMP      NOT NULL DEFAULT NOW(),
    created_by_admin_id             BIGINT
);

CREATE INDEX idx_redeem_rule_binding_active
    ON loyalty_binge_redemption_rule (binding_id, effective_from)
    WHERE effective_to IS NULL;


-- Per-binge perk overrides.  Allows a binge to disable a platform perk
-- (e.g. "we don't offer free upgrades") or customize its cost.
CREATE TABLE loyalty_binge_perk_override (
    id                      BIGSERIAL     PRIMARY KEY,
    tenant_id               BIGINT,
    binding_id              BIGINT        NOT NULL REFERENCES loyalty_binge_binding(id),
    perk_id                 BIGINT        NOT NULL REFERENCES loyalty_perk_catalog(id),
    mode                    VARCHAR(20)   NOT NULL DEFAULT 'INHERIT',  -- INHERIT / DISABLED / OVERRIDDEN
    override_point_cost     BIGINT,
    override_cooldown_hours INTEGER,
    override_params_json    TEXT,
    created_at              TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_perk_override UNIQUE (binding_id, perk_id)
);


-- Binge-specific reward inventory (free dessert, 1hr free session, etc.).
-- Unlike platform perks, these are produced by the individual business.
CREATE TABLE loyalty_binge_reward_item (
    id                      BIGSERIAL     PRIMARY KEY,
    tenant_id               BIGINT,
    binding_id              BIGINT        NOT NULL REFERENCES loyalty_binge_binding(id),
    sku                     VARCHAR(60)   NOT NULL,
    display_name            VARCHAR(120)  NOT NULL,
    description             VARCHAR(500),
    point_cost              BIGINT        NOT NULL,
    min_tier_code           VARCHAR(30),
    inventory_remaining     BIGINT,                    -- NULL = unlimited
    active                  BOOLEAN       NOT NULL DEFAULT TRUE,
    effective_from          TIMESTAMP     NOT NULL DEFAULT NOW(),
    effective_to            TIMESTAMP,
    created_at              TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_reward_sku UNIQUE (binding_id, sku)
);


-- ═══════════════════════════════════════════════════════════════════════════
--   3. MEMBER LAYER   (customer-scoped)
-- ═══════════════════════════════════════════════════════════════════════════

-- One row per customer per program (today: one program, one row per
-- customer).  The snapshot of tier state lives here — recomputed on every
-- write that could change it.
CREATE TABLE loyalty_membership (
    id                                  BIGSERIAL    PRIMARY KEY,
    tenant_id                           BIGINT,
    program_id                          BIGINT       NOT NULL REFERENCES loyalty_program(id),
    customer_id                         BIGINT       NOT NULL,
    member_number                       VARCHAR(20)  NOT NULL,

    enrolled_at                         TIMESTAMP    NOT NULL DEFAULT NOW(),
    enrollment_source                   VARCHAR(30)  NOT NULL,     -- SILENT_BOOKING / EXPLICIT_SIGNUP / SSO_GOOGLE / ADMIN_IMPORT / STATUS_MATCH / BACKFILL_V2

    current_tier_code                   VARCHAR(30)  NOT NULL DEFAULT 'BRONZE',
    tier_effective_from                 TIMESTAMP    NOT NULL DEFAULT NOW(),
    tier_effective_until                TIMESTAMP,                 -- NULL = permanent (Bronze / Lifetime)
    soft_landing_eligible               BOOLEAN      NOT NULL DEFAULT TRUE,

    qualifying_credits_window           BIGINT       NOT NULL DEFAULT 0,
    lifetime_credits                    BIGINT       NOT NULL DEFAULT 0,
    lifetime_years_at_current_tier      INTEGER      NOT NULL DEFAULT 0,

    status_match_source                 VARCHAR(120),
    status_match_expires_at             TIMESTAMP,

    active                              BOOLEAN      NOT NULL DEFAULT TRUE,
    deactivated_at                      TIMESTAMP,
    deactivation_reason                 VARCHAR(255),

    marketing_opt_in                    BOOLEAN      NOT NULL DEFAULT FALSE,
    privacy_flags_json                  TEXT,

    created_at                          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                          TIMESTAMP    NOT NULL DEFAULT NOW(),
    version                             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_membership_customer UNIQUE (program_id, customer_id),
    CONSTRAINT uq_membership_number   UNIQUE (member_number)
);

CREATE INDEX idx_membership_tier_until
    ON loyalty_membership (tier_effective_until)
    WHERE tier_effective_until IS NOT NULL;


-- Wallet split off so concurrent earn/redeem lock a small row, not the
-- whole membership profile.  1:1 with membership.
CREATE TABLE loyalty_points_wallet (
    id                      BIGSERIAL    PRIMARY KEY,
    tenant_id               BIGINT,
    membership_id           BIGINT       NOT NULL REFERENCES loyalty_membership(id),
    current_balance         BIGINT       NOT NULL DEFAULT 0,
    lifetime_earned         BIGINT       NOT NULL DEFAULT 0,
    lifetime_redeemed       BIGINT       NOT NULL DEFAULT 0,
    lifetime_expired        BIGINT       NOT NULL DEFAULT 0,
    lifetime_adjusted       BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    version                 BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_wallet_membership UNIQUE (membership_id),
    CONSTRAINT ck_balance_non_negative CHECK (current_balance >= 0)
);


-- FIFO lots — each EARN creates one.  Redeems/expirations decrement
-- remaining_points on the oldest lots first.  Crucial for correct
-- partial-expiry and clean per-binge scoping.
CREATE TABLE loyalty_points_lot (
    id                      BIGSERIAL    PRIMARY KEY,
    tenant_id               BIGINT,
    wallet_id               BIGINT       NOT NULL REFERENCES loyalty_points_wallet(id),
    binge_id                BIGINT,                            -- NULL = program-wide (welcome/birthday)
    source_type             VARCHAR(40)  NOT NULL,             -- EARN_BOOKING / BONUS_WELCOME / BONUS_BIRTHDAY / ADMIN_ADJUSTMENT / STATUS_MATCH_GRANT
    source_ref              VARCHAR(64),                       -- booking_ref / reason code

    original_points         BIGINT       NOT NULL,
    remaining_points        BIGINT       NOT NULL,
    earned_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMP    NOT NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_lot_remaining_non_negative CHECK (remaining_points >= 0),
    CONSTRAINT ck_lot_remaining_le_original CHECK (remaining_points <= original_points)
);

CREATE INDEX idx_lot_wallet_fifo
    ON loyalty_points_lot (wallet_id, earned_at)
    WHERE remaining_points > 0;

CREATE INDEX idx_lot_expiry_job
    ON loyalty_points_lot (expires_at)
    WHERE remaining_points > 0;


-- Tier-qualifying events (rolling 12-month window).  `expires_from_window_at`
-- is indexed so recalc is a range scan over a hot partial index, not a
-- full table sum.
CREATE TABLE loyalty_qualification_event (
    id                         BIGSERIAL    PRIMARY KEY,
    tenant_id                  BIGINT,
    membership_id              BIGINT       NOT NULL REFERENCES loyalty_membership(id),
    binge_id                   BIGINT,
    booking_ref                VARCHAR(20),
    event_type                 VARCHAR(40)  NOT NULL,   -- BOOKING_COMPLETED / SPEND_MILESTONE / STATUS_MATCH_GRANT / BONUS_PROMO
    qualification_credits      BIGINT       NOT NULL,
    event_at                   TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_from_window_at     TIMESTAMP    NOT NULL,

    created_at                 TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_qc_active_window
    ON loyalty_qualification_event (membership_id, expires_from_window_at)
    WHERE expires_from_window_at > '2026-01-01';  -- partial index; narrowed at maintenance time


-- Immutable ledger.  Every financial mutation of the wallet produces
-- exactly one row here.  Corrections are compensating entries.
CREATE TABLE loyalty_ledger_entry (
    id                     BIGSERIAL     PRIMARY KEY,
    tenant_id              BIGINT,
    wallet_id              BIGINT        NOT NULL REFERENCES loyalty_points_wallet(id),
    entry_type             VARCHAR(30)   NOT NULL,   -- EARN / REDEEM / EXPIRE / ADJUST / REVERSE_EARN / REVERSE_REDEEM / BONUS / STATUS_MATCH_GRANT / TRANSFER_IN / TRANSFER_OUT
    points_delta           BIGINT        NOT NULL,   -- signed
    lot_id                 BIGINT        REFERENCES loyalty_points_lot(id),
    binge_id               BIGINT,
    booking_ref            VARCHAR(20),
    actor_id               BIGINT,
    actor_role             VARCHAR(20),
    reason_code            VARCHAR(60),
    description            VARCHAR(500),
    correlation_id         VARCHAR(64),
    idempotency_key        VARCHAR(128),             -- de-dupe for retries

    created_at             TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_ledger_idempotency UNIQUE (wallet_id, entry_type, idempotency_key)
);

CREATE INDEX idx_ledger_wallet_time
    ON loyalty_ledger_entry (wallet_id, created_at DESC);

CREATE INDEX idx_ledger_booking
    ON loyalty_ledger_entry (booking_ref)
    WHERE booking_ref IS NOT NULL;


-- Non-financial lifecycle events (tier changes, rewards, status match,
-- enrollment).  Drives the customer timeline + admin audit.
CREATE TABLE loyalty_membership_event (
    id                 BIGSERIAL     PRIMARY KEY,
    tenant_id          BIGINT,
    membership_id      BIGINT        NOT NULL REFERENCES loyalty_membership(id),
    event_type         VARCHAR(40)   NOT NULL,
    from_value_json    TEXT,
    to_value_json      TEXT,
    triggered_by       VARCHAR(20)   NOT NULL,   -- SYSTEM / ADMIN / CUSTOMER
    triggered_by_id    BIGINT,
    correlation_id     VARCHAR(64),

    created_at         TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mevent_membership_time
    ON loyalty_membership_event (membership_id, created_at DESC);


-- Claimed catalog rewards + platform perks that require explicit claim.
CREATE TABLE loyalty_reward_claim (
    id                         BIGSERIAL     PRIMARY KEY,
    tenant_id                  BIGINT,
    membership_id              BIGINT        NOT NULL REFERENCES loyalty_membership(id),
    perk_id                    BIGINT        REFERENCES loyalty_perk_catalog(id),
    binge_reward_item_id       BIGINT        REFERENCES loyalty_binge_reward_item(id),
    binge_id                   BIGINT,
    booking_ref                VARCHAR(20),

    points_cost                BIGINT        NOT NULL,
    status                     VARCHAR(20)   NOT NULL,   -- RESERVED / FULFILLED / CANCELLED / EXPIRED
    fulfillment_code           VARCHAR(80),              -- QR / promo code
    fulfillment_payload_json   TEXT,

    claimed_at                 TIMESTAMP     NOT NULL DEFAULT NOW(),
    fulfilled_at               TIMESTAMP,
    expires_at                 TIMESTAMP,
    cancelled_at               TIMESTAMP,

    CONSTRAINT ck_reward_one_source CHECK (
        (perk_id IS NOT NULL AND binge_reward_item_id IS NULL)
     OR (perk_id IS NULL AND binge_reward_item_id IS NOT NULL)
    )
);

CREATE INDEX idx_reward_member_status
    ON loyalty_reward_claim (membership_id, status);

CREATE INDEX idx_reward_expiry_job
    ON loyalty_reward_claim (expires_at)
    WHERE status = 'RESERVED';


-- Guest shadow accounts — pre-signup credit accrual (Booking.com model).
-- PII is HASHED, not stored cleartext.  On signup within the retroactive
-- window, these credits are migrated into the real membership.
CREATE TABLE loyalty_guest_shadow (
    id                            BIGSERIAL     PRIMARY KEY,
    tenant_id                     BIGINT,
    email_hash                    VARCHAR(64),
    phone_hash                    VARCHAR(64),
    device_fingerprint_hash       VARCHAR(64),

    pending_points                BIGINT        NOT NULL DEFAULT 0,
    pending_qualifying_credits    BIGINT        NOT NULL DEFAULT 0,
    last_booking_ref              VARCHAR(20),

    first_seen_at                 TIMESTAMP     NOT NULL DEFAULT NOW(),
    last_seen_at                  TIMESTAMP     NOT NULL DEFAULT NOW(),
    merged_membership_id          BIGINT        REFERENCES loyalty_membership(id),
    merged_at                     TIMESTAMP,
    expires_at                    TIMESTAMP     NOT NULL
);

CREATE INDEX idx_guest_shadow_email
    ON loyalty_guest_shadow (email_hash)
    WHERE merged_membership_id IS NULL;

CREATE INDEX idx_guest_shadow_expiry
    ON loyalty_guest_shadow (expires_at)
    WHERE merged_membership_id IS NULL;


-- Status match submissions.  Config decides whether each is auto-approved
-- or enters the admin review queue.
CREATE TABLE loyalty_status_match_request (
    id                          BIGSERIAL     PRIMARY KEY,
    tenant_id                   BIGINT,
    membership_id               BIGINT        NOT NULL REFERENCES loyalty_membership(id),
    competitor_program_name     VARCHAR(120)  NOT NULL,
    competitor_tier_name        VARCHAR(60)   NOT NULL,
    proof_url                   VARCHAR(500),
    proof_payload_json          TEXT,
    requested_tier_code         VARCHAR(30)   NOT NULL,
    status                      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',   -- PENDING / APPROVED / REJECTED / CHALLENGE_ACTIVE / CHALLENGE_EXPIRED
    reviewed_by_admin_id        BIGINT,
    reviewed_at                 TIMESTAMP,
    review_notes                VARCHAR(500),
    challenge_expires_at        TIMESTAMP,

    created_at                  TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_status_match_pending
    ON loyalty_status_match_request (status, created_at)
    WHERE status = 'PENDING';


-- ═══════════════════════════════════════════════════════════════════════════
--   4. PLATFORM SEED DATA
--
--   One program, default tier ladder (Bonvoy-style validity), and the
--   initial perk catalog.  All thresholds/rates can be edited live through
--   the super-admin UI without a redeploy.
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO loyalty_program (
    code, display_name, description,
    silent_enrollment_enabled, guest_shadow_enabled, retroactive_credit_days,
    points_expiry_days, devaluation_notice_days,
    status_match_enabled, status_challenge_days,
    welcome_bonus_points, birthday_bonus_points,
    allow_negative_balance, gifting_enabled
) VALUES (
    'SK_MEMBERSHIP',
    'SK Binge Galaxy Membership',
    'One membership, every binge — status, points, and perks that follow you.',
    TRUE, TRUE, 30,
    540, 90,
    TRUE, 90,
    500, 250,
    FALSE, FALSE
);


-- Tier ladder (uses the program we just inserted).  Validity = "through
-- end of NEXT calendar year after qualifying" is modeled by
-- validity_calendar_years_after = 1 — computed at runtime.
INSERT INTO loyalty_tier_definition (
    program_id, code, display_name, rank_order,
    qualification_credits_required, qualification_window_days,
    validity_calendar_years_after, soft_landing_tier_code,
    color_hex, icon_key
)
SELECT p.id, v.code, v.display_name, v.rank_order,
       v.qc_required, 365,
       v.validity_years, v.soft_landing,
       v.color, v.icon
FROM loyalty_program p
CROSS JOIN (VALUES
    ('BRONZE',   'Bronze',   0,      0,       NULL,   NULL,      '#B08D57', 'medal'),
    ('SILVER',   'Silver',   1,      5000,    1,      'BRONZE',  '#C0C0C0', 'medal'),
    ('GOLD',     'Gold',     2,      20000,   1,      'SILVER',  '#D4AF37', 'star'),
    ('PLATINUM', 'Platinum', 3,      50000,   1,      'GOLD',    '#7C7C7C', 'crown')
) AS v(code, display_name, rank_order, qc_required, validity_years, soft_landing, color, icon)
WHERE p.code = 'SK_MEMBERSHIP';


-- Lifetime Platinum (separate tier — never demoted).
INSERT INTO loyalty_tier_definition (
    program_id, code, display_name, rank_order,
    qualification_credits_required, qualification_window_days,
    lifetime_credits_required, lifetime_years_held_required,
    validity_calendar_years_after, soft_landing_tier_code,
    color_hex, icon_key
)
SELECT p.id, 'LIFETIME_PLATINUM', 'Lifetime Platinum', 4,
       0, 365,
       250000, 10,
       NULL, NULL,
       '#1C1C1C', 'infinity'
FROM loyalty_program p
WHERE p.code = 'SK_MEMBERSHIP';


-- Initial perk catalog.  Each row points to a delivery handler key that
-- will be implemented in M4 (PerkDeliveryHandler interface).
INSERT INTO loyalty_perk_catalog (
    program_id, code, display_name, description,
    category, fulfillment_type, delivery_handler_key,
    default_point_cost, cooldown_hours
)
SELECT p.id, v.code, v.name, v.description, v.category, v.fulfillment, v.handler, v.cost, v.cooldown
FROM loyalty_program p
CROSS JOIN (VALUES
    ('TIER_DISCOUNT_PCT',        'Tier Discount',                 'Automatic percentage discount applied to every booking at your tier.',                 'FINANCIAL',  'AUTOMATIC',  'DISCOUNT_PERCENT_OF_BOOKING', 0,   0),
    ('FREE_CANCELLATION_24H',    'Free Cancellation +24h',        'Cancellation grace period extended by 24 hours beyond the binge policy.',              'FINANCIAL',  'AUTOMATIC',  'FREE_CANCELLATION_EXTENDED',  0,   0),
    ('BONUS_MULTIPLIER',         'Bonus Points Multiplier',       'Earn bonus points on every booking, scaled by your tier.',                             'FINANCIAL',  'AUTOMATIC',  'BONUS_POINTS_MULTIPLIER',     0,   0),
    ('PRIORITY_WAITLIST',        'Priority Waitlist',             'Higher priority score on any binge waitlist.',                                         'SOFT',       'AUTOMATIC',  'PRIORITY_WAITLIST',           0,   0),
    ('EARLY_ACCESS_BOOKING',     'Early Access Booking',          'Book new slots before they open to the general public.',                               'SOFT',       'AUTOMATIC',  'EARLY_ACCESS_BOOKING_WINDOW', 0,   0),
    ('BIRTHDAY_BONUS',           'Birthday Bonus Points',         'Bonus points gifted once a year around your birthday.',                                'FINANCIAL',  'AUTOMATIC',  'BIRTHDAY_BONUS_POINTS',       0,   0),
    ('WELCOME_BONUS',            'Welcome Bonus Points',          'Points gifted on enrollment so your first booking benefits immediately.',              'FINANCIAL',  'AUTOMATIC',  'WELCOME_BONUS_POINTS',        0,   0),
    ('STATUS_EXTENSION',         'Status Extension Grant',        'Admin-granted extension of your current tier validity.',                               'INVISIBLE',  'MANUAL',     'STATUS_EXTENSION_GRANT',      0,   0),
    ('REWARD_CATALOG',           'Rewards Catalog',               'Redeem points for binge-specific rewards, upgrades, and merchandise.',                 'FINANCIAL',  'ON_DEMAND',  'REWARD_CATALOG_CLAIM',        0,   0),
    ('SURPRISE_DELIGHT',         'Surprise & Delight',            'Occasional unprompted bonus points for the top 1% of members each month.',             'INVISIBLE',  'AUTOMATIC',  'SURPRISE_DELIGHT_BUDGET',     0,   0)
) AS v(code, name, description, category, fulfillment, handler, cost, cooldown)
WHERE p.code = 'SK_MEMBERSHIP';


-- Bronze: welcome bonus only (instant gratification).
INSERT INTO loyalty_tier_perk (tier_definition_id, perk_id, auto_grant, sort_order)
SELECT t.id, p.id, TRUE, 1
FROM loyalty_tier_definition t
JOIN loyalty_perk_catalog p ON p.program_id = t.program_id
WHERE t.code = 'BRONZE'
  AND p.code IN ('WELCOME_BONUS', 'BIRTHDAY_BONUS', 'REWARD_CATALOG', 'SURPRISE_DELIGHT');


-- Silver: priority waitlist, birthday bonus, 5% discount, reward catalog.
INSERT INTO loyalty_tier_perk (tier_definition_id, perk_id, auto_grant, sort_order)
SELECT t.id, p.id, TRUE,
       CASE p.code
           WHEN 'TIER_DISCOUNT_PCT'    THEN 1
           WHEN 'BIRTHDAY_BONUS'       THEN 2
           WHEN 'PRIORITY_WAITLIST'    THEN 3
           WHEN 'REWARD_CATALOG'       THEN 4
           WHEN 'SURPRISE_DELIGHT'     THEN 5
           ELSE 99
       END
FROM loyalty_tier_definition t
JOIN loyalty_perk_catalog p ON p.program_id = t.program_id
WHERE t.code = 'SILVER'
  AND p.code IN ('TIER_DISCOUNT_PCT', 'BIRTHDAY_BONUS', 'PRIORITY_WAITLIST', 'REWARD_CATALOG', 'SURPRISE_DELIGHT');


-- Gold: Silver + free cancellation +24h, bonus multiplier.
INSERT INTO loyalty_tier_perk (tier_definition_id, perk_id, auto_grant, sort_order)
SELECT t.id, p.id, TRUE,
       CASE p.code
           WHEN 'TIER_DISCOUNT_PCT'       THEN 1
           WHEN 'BONUS_MULTIPLIER'        THEN 2
           WHEN 'FREE_CANCELLATION_24H'   THEN 3
           WHEN 'BIRTHDAY_BONUS'          THEN 4
           WHEN 'PRIORITY_WAITLIST'       THEN 5
           WHEN 'REWARD_CATALOG'          THEN 6
           WHEN 'SURPRISE_DELIGHT'        THEN 7
           ELSE 99
       END
FROM loyalty_tier_definition t
JOIN loyalty_perk_catalog p ON p.program_id = t.program_id
WHERE t.code = 'GOLD'
  AND p.code IN ('TIER_DISCOUNT_PCT', 'BONUS_MULTIPLIER', 'FREE_CANCELLATION_24H',
                 'BIRTHDAY_BONUS', 'PRIORITY_WAITLIST', 'REWARD_CATALOG', 'SURPRISE_DELIGHT');


-- Platinum: Gold + early access booking, status extension (manual).
INSERT INTO loyalty_tier_perk (tier_definition_id, perk_id, auto_grant, sort_order)
SELECT t.id, p.id,
       CASE WHEN p.code = 'STATUS_EXTENSION' THEN FALSE ELSE TRUE END,
       CASE p.code
           WHEN 'TIER_DISCOUNT_PCT'       THEN 1
           WHEN 'BONUS_MULTIPLIER'        THEN 2
           WHEN 'FREE_CANCELLATION_24H'   THEN 3
           WHEN 'EARLY_ACCESS_BOOKING'    THEN 4
           WHEN 'BIRTHDAY_BONUS'          THEN 5
           WHEN 'PRIORITY_WAITLIST'       THEN 6
           WHEN 'REWARD_CATALOG'          THEN 7
           WHEN 'STATUS_EXTENSION'        THEN 8
           WHEN 'SURPRISE_DELIGHT'        THEN 9
           ELSE 99
       END
FROM loyalty_tier_definition t
JOIN loyalty_perk_catalog p ON p.program_id = t.program_id
WHERE t.code = 'PLATINUM'
  AND p.code IN ('TIER_DISCOUNT_PCT', 'BONUS_MULTIPLIER', 'FREE_CANCELLATION_24H',
                 'EARLY_ACCESS_BOOKING', 'BIRTHDAY_BONUS', 'PRIORITY_WAITLIST',
                 'REWARD_CATALOG', 'STATUS_EXTENSION', 'SURPRISE_DELIGHT');


-- Lifetime Platinum inherits the Platinum perk set.
INSERT INTO loyalty_tier_perk (tier_definition_id, perk_id, auto_grant, sort_order)
SELECT lp.id, tp.perk_id, tp.auto_grant, tp.sort_order
FROM loyalty_tier_definition lp
JOIN loyalty_tier_definition plat ON plat.program_id = lp.program_id AND plat.code = 'PLATINUM'
JOIN loyalty_tier_perk tp ON tp.tier_definition_id = plat.id
WHERE lp.code = 'LIFETIME_PLATINUM';


-- ═══════════════════════════════════════════════════════════════════════════
--   5. MULTI-TENANT FUTURE GUARD (RLS stubs — disabled today)
--
--   Every v2 table has a nullable tenant_id column.  When the platform
--   goes multi-tenant, switching each table to tenant-scoped reads is
--   an additive change: enable RLS + a policy, no schema migration.
--   The policies are intentionally left as comments below so this file
--   is a single source of truth for that rollout.
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Example (to enable at multi-tenant cutover):
--
--   ALTER TABLE loyalty_membership ENABLE ROW LEVEL SECURITY;
--   CREATE POLICY loyalty_membership_tenant_isolation
--     ON loyalty_membership
--     USING (tenant_id = current_setting('app.current_tenant_id', true)::BIGINT
--            OR tenant_id IS NULL);
--
-- Repeat for every loyalty_* table.
