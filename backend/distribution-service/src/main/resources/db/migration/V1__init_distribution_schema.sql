-- V1 — distribution bounded context.
-- Design: docs/distribution/05-DISTRIBUTION-CONSOLE-DESIGN-V2.md §13 G-A.
--
-- THE INVARIANT THIS SCHEMA EXISTS TO PROTECT:
--   no availability rows, no booking rows, no prices.
-- Availability stays in availability-service, reservations and pricing in
-- booking-service. This context owns only the things that describe a CONNECTION to
-- the outside world: who we are connected to, what they can do, what we published,
-- what arrived, what we are owed. Every availability answer is derived at read time
-- from the owning service; a cached copy here would become a second inventory truth
-- and reintroduce exactly the oversell class the V81 backstop exists to prevent.
--
-- Three concepts the previous design collapsed into one word, "channel":
--   * provider    — the technical system we exchange data with (Bokun, Viator API)
--   * destination — the marketplace a traveller actually buys on (Viator, GetYourGuide)
--   * booking source — where a booking is ATTRIBUTED
-- A reservation can arrive THROUGH Bokun while being sold ON Viator, and commission,
-- traveller support and attribution all follow the destination, not the provider.

-- ─────────────────────────────────────────────────────────────────────────
-- 1. Provider catalogue — platform-owned, super-admin managed
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE providers (
    code                  VARCHAR(40)  PRIMARY KEY,
    display_name          VARCHAR(120) NOT NULL,
    -- CONNECTIVITY: a pipe to other destinations (Bokun).
    -- DESTINATION:  a marketplace only.
    -- BOTH:         Viator, which is its own connectivity API and its own marketplace.
    provider_kind         VARCHAR(20)  NOT NULL,
    -- How a venue authorises us. Deliberately NOT assumed to be an API key:
    -- Google Things to Do is an SFTP feed behind a content licence agreement, and
    -- Actions Center is basic auth rotated every six months.
    auth_method           VARCHAR(24)  NOT NULL,
    certification_state   VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    -- Empty array = no country restriction recorded. A connection may only reach a
    -- destination that operates in the venue's country (global scope, decision B1).
    supported_countries   TEXT[]       NOT NULL DEFAULT '{}',
    docs_url              VARCHAR(500),
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_provider_kind CHECK (provider_kind IN ('CONNECTIVITY', 'DESTINATION', 'BOTH')),
    CONSTRAINT ck_provider_auth CHECK (auth_method IN
        ('OAUTH', 'API_KEY', 'SFTP_FEED', 'PLATFORM_MANAGED', 'CONTRACT_ONLY')),
    CONSTRAINT ck_provider_cert CHECK (certification_state IN
        ('NONE', 'SELF', 'PROVIDER_REVIEWED', 'PILOT_REQUIRED'))
);

COMMENT ON TABLE providers IS
    'Technical systems SK Binge can exchange data with. Platform-owned catalogue; venues create connections TO these.';

-- ─────────────────────────────────────────────────────────────────────────
-- 2. Capabilities — rows, not a bitmask
-- ─────────────────────────────────────────────────────────────────────────
-- One row per (provider, capability). A missing row reads as FALSE, which is the
-- safe direction: the UI must never offer an action a provider cannot perform, and
-- an unknown capability should disable a button rather than enable one. `notes`
-- carries the evidence for the value so a reviewer can audit a claim rather than
-- trust it -- several capabilities here are UNVERIFIED against official docs and
-- must stay false until someone confirms them.
CREATE TABLE provider_capabilities (
    provider_code   VARCHAR(40)  NOT NULL REFERENCES providers(code) ON DELETE CASCADE,
    capability_key  VARCHAR(60)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    int_value       INTEGER,
    notes           VARCHAR(500),
    PRIMARY KEY (provider_code, capability_key)
);

COMMENT ON COLUMN provider_capabilities.enabled IS
    'Absence of a row means FALSE. Fail closed: an unproven capability must not light up a control.';
COMMENT ON COLUMN provider_capabilities.notes IS
    'Evidence for the value (official doc reference, or UNVERIFIED). Reviewers audit claims here.';

-- ─────────────────────────────────────────────────────────────────────────
-- 3. Sales destinations
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE destinations (
    code                        VARCHAR(40)  PRIMARY KEY,
    display_name                VARCHAR(120) NOT NULL,
    -- Which provider operates this marketplace. Viator the destination is operated
    -- by Viator the provider; Viator reached via Bokun is the same destination
    -- through a different connection -- which is the entire point of the split.
    operated_by_provider_code   VARCHAR(40)  NOT NULL REFERENCES providers(code),
    supported_countries         TEXT[]       NOT NULL DEFAULT '{}',
    -- Feed-only destinations (Google Things to Do) never deliver reservations; the
    -- traveller completes checkout on SK Binge. Reservation screens must not render
    -- rows for them, and their conversions are canonical bookings with attribution.
    delivers_reservations       BOOLEAN      NOT NULL DEFAULT TRUE,
    active                      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ─────────────────────────────────────────────────────────────────────────
-- 4. Connections — a VENUE's authorisation to one provider
-- ─────────────────────────────────────────────────────────────────────────
-- binge_id is NOT NULL by design (G-D). The venue is the supplier of record
-- (decision B2) because GetYourGuide explicitly refuses "resellers, aggregators,
-- online travel agencies" as supply partners. A platform-wide connection would
-- contradict the contracting model the whole strategy rests on.
CREATE TABLE connections (
    id                     BIGSERIAL    PRIMARY KEY,
    binge_id               BIGINT       NOT NULL,
    provider_code          VARCHAR(40)  NOT NULL REFERENCES providers(code),
    status                 VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    environment            VARCHAR(12)  NOT NULL DEFAULT 'SANDBOX',
    -- A POINTER into the secrets manager, never the secret. No API returns it and
    -- nothing logs it; the frontend receives a masked hint only.
    credential_ref         VARCHAR(200),
    credential_hint        VARCHAR(40),
    credential_expires_at  TIMESTAMP,
    last_verified_at       TIMESTAMP,
    -- Pause stops ALL traffic. Stop-sell (per destination, below) is different: it
    -- stops NEW sales while honouring reservations already taken.
    paused_at              TIMESTAMP,
    paused_reason          VARCHAR(300),
    created_by             BIGINT,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_connection_status CHECK (status IN
        ('PENDING', 'AWAITING_PROVIDER', 'ACTIVE', 'DEGRADED', 'PAUSED', 'REVOKED')),
    CONSTRAINT ck_connection_env CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    -- One connection per venue per provider per environment: sandbox and production
    -- coexist, duplicates of either do not.
    CONSTRAINT uk_connection_binge_provider_env UNIQUE (binge_id, provider_code, environment)
);

CREATE INDEX idx_connection_binge ON connections (binge_id);
CREATE INDEX idx_connection_expiring ON connections (credential_expires_at)
    WHERE credential_expires_at IS NOT NULL;

COMMENT ON COLUMN connections.credential_ref IS
    'Secrets-manager pointer. NEVER the secret itself, and never returned by any API.';

-- ─────────────────────────────────────────────────────────────────────────
-- 5. Which destinations a connection reaches, and on what commercial terms
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE connection_destinations (
    id                        BIGSERIAL   PRIMARY KEY,
    connection_id             BIGINT      NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
    destination_code          VARCHAR(40) NOT NULL REFERENCES destinations(code),
    enabled                   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Commission in basis points (2000 = 20%), matching the existing Stripe Connect
    -- convention so the platform has one way of expressing a rate.
    commission_bps            INTEGER,
    -- The v1 design assumed a single agency model -- "you collect, the channel takes
    -- commission". That is NOT how the two most relevant destinations work: Viator is
    -- merchant of record and pays the net rate AFTER the experience, and GetYourGuide
    -- collects as Merchant of Record and pays retail minus commission monthly.
    -- Telling a venue it collects at checkout would misstate its cash flow.
    payment_responsibility    VARCHAR(28) NOT NULL DEFAULT 'CHANNEL_COLLECTS',
    settlement_model          VARCHAR(28) NOT NULL DEFAULT 'COMMISSION_SETTLEMENT',
    -- Hold back N concurrent slots from this destination (restored from the original
    -- dossier's G4/DIST-R6, which the v2 design had dropped).
    safety_inventory          INTEGER     NOT NULL DEFAULT 0,
    -- Stop NEW sales but honour existing reservations. Distinct from pausing the
    -- whole connection.
    stop_sell                 BOOLEAN     NOT NULL DEFAULT FALSE,
    stop_sell_reason          VARCHAR(300),
    created_at                TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conn_dest UNIQUE (connection_id, destination_code),
    CONSTRAINT ck_commission_bps CHECK (commission_bps IS NULL OR commission_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_safety_inventory CHECK (safety_inventory >= 0),
    CONSTRAINT ck_payment_responsibility CHECK (payment_responsibility IN
        ('VENUE_COLLECTS', 'SK_BINGE_COLLECTS', 'CHANNEL_COLLECTS',
         'PAY_AT_VENUE', 'VIRTUAL_CARD', 'MIXED_OR_PARTIAL')),
    CONSTRAINT ck_settlement_model CHECK (settlement_model IN
        ('COMMISSION_SETTLEMENT', 'NET_RATE_SETTLEMENT', 'DIRECT_SETTLEMENT'))
);

-- ─────────────────────────────────────────────────────────────────────────
-- 6. Listing mappings and readiness
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE listing_mappings (
    id                          BIGSERIAL    PRIMARY KEY,
    connection_destination_id   BIGINT       NOT NULL
        REFERENCES connection_destinations(id) ON DELETE CASCADE,
    -- FK to booking_db.event_types. Deliberately NOT a database foreign key: it
    -- crosses a service boundary, and enforcing it here would couple two schemas
    -- that must be independently deployable.
    event_type_id               BIGINT       NOT NULL,
    binge_id                    BIGINT       NOT NULL,
    external_product_id         VARCHAR(200),
    external_option_ids         TEXT[]       NOT NULL DEFAULT '{}',
    publish_state               VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    -- A listing may not go LIVE below 100. Requirements differ per destination, so
    -- readiness is per (listing x destination), not per listing.
    readiness_pct               INTEGER      NOT NULL DEFAULT 0,
    blocking_reasons            TEXT[]       NOT NULL DEFAULT '{}',
    last_published_at           TIMESTAMP,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_listing_mapping UNIQUE (connection_destination_id, event_type_id),
    CONSTRAINT ck_publish_state CHECK (publish_state IN
        ('DRAFT', 'READY', 'PUBLISHING', 'LIVE', 'PAUSED', 'BLOCKED', 'FAILED')),
    CONSTRAINT ck_readiness_pct CHECK (readiness_pct BETWEEN 0 AND 100),
    -- The rule stated as schema rather than as prose in a service: a listing cannot
    -- be LIVE while incomplete.
    CONSTRAINT ck_live_requires_ready CHECK (publish_state <> 'LIVE' OR readiness_pct = 100)
);

CREATE INDEX idx_listing_binge ON listing_mappings (binge_id);
CREATE UNIQUE INDEX uk_listing_external_product
    ON listing_mappings (connection_destination_id, external_product_id)
    WHERE external_product_id IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────
-- 7. Reservation inbox — raw inbound, persisted BEFORE canonical creation
-- ─────────────────────────────────────────────────────────────────────────
-- Persisting first is what turns a rejected reservation into a visible, explainable
-- row instead of a lost booking.
CREATE TABLE reservation_inbox (
    id                  BIGSERIAL    PRIMARY KEY,
    connection_id       BIGINT       NOT NULL REFERENCES connections(id),
    destination_code    VARCHAR(40)  NOT NULL REFERENCES destinations(code),
    external_ref        VARCHAR(128) NOT NULL,
    message_type        VARCHAR(20)  NOT NULL,
    -- Ordering (G-C). V85's unique index makes CREATION idempotent but says nothing
    -- about a cancel arriving before the modify it supersedes -- ordinary with
    -- at-least-once delivery. Apply only when external_sequence exceeds the last
    -- applied value; otherwise mark SUPERSEDED and keep the row.
    external_sequence   BIGINT,
    -- Which basis actually ordered this message. A reconciliation run must be able to
    -- distinguish "ordered by the provider" from "ordered by luck".
    ordering_basis      VARCHAR(20)  NOT NULL DEFAULT 'RECEIPT_ORDER',
    payload_json        TEXT         NOT NULL,
    received_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at        TIMESTAMP,
    status              VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    -- Set once the canonical booking exists in booking-service. This context never
    -- stores booking detail -- only the reference.
    booking_ref         VARCHAR(32),
    reject_reason       VARCHAR(500),
    CONSTRAINT ck_inbox_message_type CHECK (message_type IN
        ('CREATE', 'MODIFY', 'CANCEL', 'ACKNOWLEDGE')),
    CONSTRAINT ck_inbox_status CHECK (status IN
        ('RECEIVED', 'APPLIED', 'REJECTED', 'SUPERSEDED', 'FAILED')),
    CONSTRAINT ck_inbox_ordering_basis CHECK (ordering_basis IN
        ('PROVIDER_SEQUENCE', 'PROVIDER_TIMESTAMP', 'RECEIPT_ORDER'))
);

-- The same message delivered twice must not be processed twice.
CREATE UNIQUE INDEX uk_inbox_message
    ON reservation_inbox (connection_id, external_ref, message_type, COALESCE(external_sequence, -1));
CREATE INDEX idx_inbox_unprocessed ON reservation_inbox (status, received_at)
    WHERE status IN ('RECEIVED', 'FAILED');

-- ─────────────────────────────────────────────────────────────────────────
-- 8. Settlements — money a third party holds or owes
-- ─────────────────────────────────────────────────────────────────────────
-- All amounts are MINOR-UNIT BIGINTs, matching the platform money contract.
-- Two currencies are tracked on purpose: a destination may remit in one currency
-- while the venue banks in another, and the variance between them is a real
-- reconciliation category rather than an error (G-E, under global scope B1).
CREATE TABLE settlement_records (
    id                        BIGSERIAL    PRIMARY KEY,
    booking_ref               VARCHAR(32)  NOT NULL,
    binge_id                  BIGINT       NOT NULL,
    connection_id             BIGINT       REFERENCES connections(id),
    destination_code          VARCHAR(40)  NOT NULL REFERENCES destinations(code),

    settlement_currency       VARCHAR(3)   NOT NULL,
    gross_minor               BIGINT       NOT NULL DEFAULT 0,
    taxes_minor               BIGINT       NOT NULL DEFAULT 0,
    fees_minor                BIGINT       NOT NULL DEFAULT 0,
    commission_minor          BIGINT       NOT NULL DEFAULT 0,
    platform_fee_minor        BIGINT       NOT NULL DEFAULT 0,
    venue_net_minor           BIGINT       NOT NULL DEFAULT 0,

    venue_payout_currency     VARCHAR(3),
    fx_rate_at_payout         NUMERIC(18, 8),

    collected_by              VARCHAR(28)  NOT NULL,
    deposit_minor             BIGINT       NOT NULL DEFAULT 0,
    outstanding_minor         BIGINT       NOT NULL DEFAULT 0,

    expected_payout_at        DATE,
    actual_payout_at          DATE,
    actual_payout_minor       BIGINT,

    -- Refund and cancellation-charge responsibility are separate questions and can
    -- land on different parties for the same booking.
    refund_responsibility     VARCHAR(16),
    cancellation_charge_responsibility VARCHAR(16),

    settlement_status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reconciliation_status     VARCHAR(20)  NOT NULL DEFAULT 'UNMATCHED',
    variance_minor            BIGINT       NOT NULL DEFAULT 0,

    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_settlement_booking_dest UNIQUE (booking_ref, destination_code),
    CONSTRAINT ck_settlement_collected_by CHECK (collected_by IN
        ('VENUE', 'SK_BINGE', 'CHANNEL', 'PAY_AT_VENUE', 'VIRTUAL_CARD', 'MIXED')),
    CONSTRAINT ck_settlement_status CHECK (settlement_status IN
        ('PENDING', 'EXPECTED', 'PAID', 'SHORT_PAID', 'OVER_PAID', 'DISPUTED', 'WRITTEN_OFF')),
    CONSTRAINT ck_reconciliation_status CHECK (reconciliation_status IN
        ('UNMATCHED', 'MATCHED', 'VARIANCE', 'INVESTIGATING', 'RESOLVED')),
    CONSTRAINT ck_refund_responsibility CHECK (refund_responsibility IS NULL
        OR refund_responsibility IN ('VENUE', 'CHANNEL', 'SK_BINGE')),
    CONSTRAINT ck_cancel_charge_responsibility CHECK (cancellation_charge_responsibility IS NULL
        OR cancellation_charge_responsibility IN ('VENUE', 'CHANNEL', 'SK_BINGE')),
    CONSTRAINT ck_settlement_currency_iso CHECK (settlement_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_venue_payout_currency_iso CHECK (venue_payout_currency IS NULL
        OR venue_payout_currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_settlement_binge_status ON settlement_records (binge_id, settlement_status);
CREATE INDEX idx_settlement_expected_payout ON settlement_records (expected_payout_at)
    WHERE settlement_status IN ('PENDING', 'EXPECTED');

-- ─────────────────────────────────────────────────────────────────────────
-- 9. Sync state — named states, not "synced N minutes ago"
-- ─────────────────────────────────────────────────────────────────────────
-- Every researched provider behaves differently: Viator/OCTO PULL on demand,
-- GetYourGuide fetches on a schedule (~8-daily for 365 days), Google is a
-- full-replacement feed that must be refreshed within 30 days or products are
-- removed. A single "last synced" timestamp cannot describe those honestly.
CREATE TABLE sync_state (
    id                          BIGSERIAL   PRIMARY KEY,
    connection_destination_id   BIGINT      NOT NULL UNIQUE
        REFERENCES connection_destinations(id) ON DELETE CASCADE,
    last_realtime_at            TIMESTAMP,
    last_delta_ack_at           TIMESTAMP,
    last_feed_published_at      TIMESTAMP,
    last_reconciled_at          TIMESTAMP,
    last_full_resync_at         TIMESTAMP,
    accepted_inventory_version  BIGINT,
    -- When the provider considers our data stale. Alarm EARLIER than the provider's
    -- deadline: Google removes products at 30 days, so we warn at 21.
    stale_after                 TIMESTAMP,
    consecutive_failures        INTEGER     NOT NULL DEFAULT 0,
    last_error                  VARCHAR(1000),
    last_error_at               TIMESTAMP,
    updated_at                  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_consecutive_failures CHECK (consecutive_failures >= 0)
);

CREATE INDEX idx_sync_state_stale ON sync_state (stale_after)
    WHERE stale_after IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────
-- 10. Seed the catalogue from RESEARCHED behaviour
-- ─────────────────────────────────────────────────────────────────────────
-- Seeded inactive. Nothing is connectable until a super-admin activates it, and
-- activation should follow the commercial confirmation still outstanding: whether
-- any experiences reseller will list PRIVATE VENUE HIRE at all.
INSERT INTO providers (code, display_name, provider_kind, auth_method, certification_state,
                       supported_countries, docs_url, active) VALUES
  ('SIMULATOR', 'OCTO Provider Simulator', 'BOTH', 'PLATFORM_MANAGED', 'NONE',
   '{}', NULL, TRUE),
  ('BOKUN', 'Bokun (Tripadvisor)', 'CONNECTIVITY', 'API_KEY', 'PROVIDER_REVIEWED',
   '{}', 'https://bokun.dev/', FALSE),
  ('VIATOR', 'Viator', 'BOTH', 'API_KEY', 'PILOT_REQUIRED',
   '{}', 'https://docs.viator.com/supplier-api/', FALSE),
  ('GETYOURGUIDE', 'GetYourGuide', 'BOTH', 'API_KEY', 'PROVIDER_REVIEWED',
   '{}', 'https://supply.getyourguide.support/', FALSE),
  ('GOOGLE_TTD', 'Google Things to Do', 'DESTINATION', 'SFTP_FEED', 'PROVIDER_REVIEWED',
   '{}', 'https://developers.google.com/actions-center/verticals/things-to-do/', FALSE);

INSERT INTO destinations (code, display_name, operated_by_provider_code, delivers_reservations, active) VALUES
  ('SIMULATOR',    'Simulator Marketplace', 'SIMULATOR',    TRUE,  TRUE),
  ('VIATOR',       'Viator',                'VIATOR',       TRUE,  FALSE),
  ('GETYOURGUIDE', 'GetYourGuide',          'GETYOURGUIDE', TRUE,  FALSE),
  -- delivers_reservations = FALSE: Things to Do is a product FEED plus a deep link.
  -- Google never takes the booking and nothing flows back; a Google conversion is a
  -- canonical SK Binge booking carrying an attribution source. The v1 design showed
  -- a Google reservation in the inbox -- that object does not exist.
  ('GOOGLE_TTD',   'Google Things to Do',   'GOOGLE_TTD',   FALSE, FALSE);

-- Capabilities. Absent row = false; `notes` records the evidence, and UNVERIFIED
-- entries stay disabled until someone confirms them with a provider.
INSERT INTO provider_capabilities (provider_code, capability_key, enabled, int_value, notes) VALUES
  -- Simulator: everything on, so the conformance harness exercises every path.
  ('SIMULATOR', 'deliversReservations',      TRUE,  NULL, 'Conformance harness'),
  ('SIMULATOR', 'supportsModification',      TRUE,  NULL, 'Conformance harness'),
  ('SIMULATOR', 'supportsCancellation',      TRUE,  NULL, 'Conformance harness'),
  ('SIMULATOR', 'supportsCounterOffer',      TRUE,  NULL, 'Conformance harness'),
  ('SIMULATOR', 'realTimeAvailabilityCheck', TRUE,  NULL, 'Conformance harness'),

  ('VIATOR', 'deliversReservations',      TRUE,  NULL, 'Supplier API: Viator calls the supplier system (pull)'),
  ('VIATOR', 'supportsModification',      TRUE,  NULL, 'Supplier API documents booking amendment'),
  ('VIATOR', 'supportsCancellation',      TRUE,  NULL, 'Supplier API documents booking cancellation'),
  ('VIATOR', 'realTimeAvailabilityCheck', TRUE,  NULL, 'Batch + real-time availability are mandatory endpoints'),
  ('VIATOR', 'supportsCounterOffer',      FALSE, NULL, 'UNVERIFIED - fail closed until confirmed'),
  ('VIATOR', 'channelCollectsPayment',    TRUE,  NULL, 'Merchant of record; pays supplier net rate AFTER the experience'),

  ('GETYOURGUIDE', 'deliversReservations',   TRUE,  NULL, 'Supplier API via Integrator Portal'),
  ('GETYOURGUIDE', 'supportsCancellation',   TRUE,  NULL, 'Supplier API'),
  ('GETYOURGUIDE', 'scheduledAvailabilityFetch', TRUE, 8, 'Default fetch ~every 8 days for 365 days'),
  ('GETYOURGUIDE', 'supportsCounterOffer',   FALSE, NULL, 'UNVERIFIED - fail closed'),
  ('GETYOURGUIDE', 'channelCollectsPayment', TRUE,  NULL, 'Merchant of Record as commercial agent; retail minus 20-35% commission, monthly'),

  ('BOKUN', 'deliversReservations',   TRUE,  NULL, 'Channel Manager API plugin lifecycle'),
  ('BOKUN', 'supportsModification',   TRUE,  NULL, 'Plugin lifecycle documents booking amendment'),
  ('BOKUN', 'supportsCancellation',   TRUE,  NULL, 'Plugin lifecycle documents cancellation'),
  ('BOKUN', 'supportsCounterOffer',   FALSE, NULL, 'UNVERIFIED - fail closed'),

  -- Google is the clearest case for capability-driven UI: almost everything is false.
  ('GOOGLE_TTD', 'deliversReservations', FALSE, NULL, 'Feed + deep link; users complete transactions on the partner site'),
  ('GOOGLE_TTD', 'supportsModification', FALSE, NULL, 'No reservation exists to modify'),
  ('GOOGLE_TTD', 'supportsCancellation', FALSE, NULL, 'No reservation exists to cancel'),
  ('GOOGLE_TTD', 'feedBased',            TRUE,  NULL, 'SFTP JSON, FULL replacement each upload'),
  ('GOOGLE_TTD', 'feedMaxStalenessDays', TRUE,  30,   'Products taken down after 30 days; alarm at 21'),
  ('GOOGLE_TTD', 'referralAttribution',  TRUE,  NULL, 'Conversions are canonical SK Binge bookings with an attribution source');
