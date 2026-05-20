-- ============================================================================
-- V39: Production-grade taxes, FX locking, invoices, credit notes, ledger.
--
-- This migration extends V38 (taxes_and_currencies) into a real-world finance
-- substrate without breaking existing data. All booking columns are added as
-- NULLABLE first; old bookings remain readable.
--
-- Tables introduced:
--   - billing_addresses
--   - booking_price_snapshots   (immutable per-booking checkout snapshot)
--   - fx_rate_locks             (TTL-based FX quote lock)
--   - invoices, invoice_lines
--   - credit_notes
--   - ledger_entries            (immutable, append-only financial journal)
--
-- tax_rules and currency_rates are extended with jurisdiction + capability
-- columns. The legacy GST seed continues to work because every new column
-- is nullable / has a sensible default.
-- ============================================================================

-- ── 1) tax_rules: jurisdiction + lifecycle + audit ──────────────────────────
ALTER TABLE tax_rules
    ADD COLUMN IF NOT EXISTS state_code     VARCHAR(16),
    ADD COLUMN IF NOT EXISTS city           VARCHAR(120),
    ADD COLUMN IF NOT EXISTS postal_code    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS product_type   VARCHAR(40),     -- e.g. BOOKING, ADDON, GUEST_FEE, ALL
    ADD COLUMN IF NOT EXISTS customer_type  VARCHAR(20),     -- B2C, B2B, ALL
    ADD COLUMN IF NOT EXISTS tax_type       VARCHAR(40)  NOT NULL DEFAULT 'GENERIC', -- GST, VAT, SALES_TAX, SERVICE_TAX
    ADD COLUMN IF NOT EXISTS effective_from TIMESTAMP,
    ADD COLUMN IF NOT EXISTS effective_to   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS rule_version   INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS created_by     VARCHAR(120),
    ADD COLUMN IF NOT EXISTS updated_by     VARCHAR(120);

CREATE INDEX IF NOT EXISTS idx_tax_rules_country_state ON tax_rules(country_code, state_code);
CREATE INDEX IF NOT EXISTS idx_tax_rules_postal        ON tax_rules(postal_code);
CREATE INDEX IF NOT EXISTS idx_tax_rules_effective     ON tax_rules(effective_from, effective_to);

-- ── 2) currency_rates: capability flags + provenance ────────────────────────
ALTER TABLE currency_rates
    ADD COLUMN IF NOT EXISTS supports_display    BOOLEAN  NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS supports_payment    BOOLEAN  NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS supports_settlement BOOLEAN  NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS fx_source           VARCHAR(40)  NOT NULL DEFAULT 'MANUAL', -- MANUAL, ECB, OPENEXCHANGE, FIXER
    ADD COLUMN IF NOT EXISTS created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_by          VARCHAR(120);

-- Mark the base currency (INR) as also payment + settlement capable, since
-- Razorpay supports it in INR by default.
UPDATE currency_rates
   SET supports_payment    = TRUE,
       supports_settlement = TRUE
 WHERE is_base = TRUE;

-- ── 3) bookings: separate currency roles + snapshot link + billing link ─────
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS base_currency_code       VARCHAR(8),
    ADD COLUMN IF NOT EXISTS display_currency_code    VARCHAR(8),
    ADD COLUMN IF NOT EXISTS payment_currency_code    VARCHAR(8),
    ADD COLUMN IF NOT EXISTS settlement_currency_code VARCHAR(8),
    ADD COLUMN IF NOT EXISTS price_snapshot_id        BIGINT,
    ADD COLUMN IF NOT EXISTS billing_address_id       BIGINT,
    ADD COLUMN IF NOT EXISTS fx_locked_until          TIMESTAMP,
    ADD COLUMN IF NOT EXISTS calculation_version      INTEGER NOT NULL DEFAULT 1;

-- Backfill: every existing booking is fully INR (display = payment = settlement).
UPDATE bookings
   SET base_currency_code       = COALESCE(base_currency_code, currency_code, 'INR'),
       display_currency_code    = COALESCE(display_currency_code, currency_code, 'INR'),
       payment_currency_code    = COALESCE(payment_currency_code, currency_code, 'INR'),
       settlement_currency_code = COALESCE(settlement_currency_code, 'INR')
 WHERE base_currency_code IS NULL
    OR display_currency_code IS NULL
    OR payment_currency_code IS NULL
    OR settlement_currency_code IS NULL;

-- ── 4) billing_addresses ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS billing_addresses (
    id              BIGSERIAL    PRIMARY KEY,
    customer_id     BIGINT,                 -- nullable: walk-in / admin booking
    booking_ref     VARCHAR(20),            -- denormalized convenience
    full_name       VARCHAR(160),
    company_name    VARCHAR(200),
    tax_id          VARCHAR(64),            -- GSTIN / VAT ID / EIN
    line1           VARCHAR(200) NOT NULL,
    line2           VARCHAR(200),
    city            VARCHAR(120),
    state_code      VARCHAR(16),
    postal_code     VARCHAR(20),
    country_code    VARCHAR(8)   NOT NULL,
    email           VARCHAR(160),
    phone           VARCHAR(40),
    phone_country_code VARCHAR(8),
    customer_type   VARCHAR(20)  NOT NULL DEFAULT 'B2C',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_billing_addr_customer ON billing_addresses(customer_id);
CREATE INDEX IF NOT EXISTS idx_billing_addr_country  ON billing_addresses(country_code);

-- ── 5) booking_price_snapshots (immutable) ──────────────────────────────────
CREATE TABLE IF NOT EXISTS booking_price_snapshots (
    id                       BIGSERIAL    PRIMARY KEY,
    booking_ref              VARCHAR(20),                 -- nullable until booking is persisted
    binge_id                 BIGINT,
    customer_id              BIGINT,
    base_currency_code       VARCHAR(8)   NOT NULL,
    display_currency_code    VARCHAR(8)   NOT NULL,
    payment_currency_code    VARCHAR(8)   NOT NULL,
    settlement_currency_code VARCHAR(8)   NOT NULL,
    subtotal_amount          NUMERIC(14,4) NOT NULL,
    surge_amount             NUMERIC(14,4) NOT NULL DEFAULT 0,
    loyalty_discount         NUMERIC(14,4) NOT NULL DEFAULT 0,
    coupon_discount          NUMERIC(14,4) NOT NULL DEFAULT 0,
    platform_fee             NUMERIC(14,4) NOT NULL DEFAULT 0,
    tax_amount               NUMERIC(14,4) NOT NULL DEFAULT 0,
    final_total_base         NUMERIC(14,4) NOT NULL,      -- in base currency
    display_total            NUMERIC(14,4) NOT NULL,
    payment_total            NUMERIC(14,4) NOT NULL,
    settlement_total         NUMERIC(14,4) NOT NULL,
    fx_rate_base_to_display  NUMERIC(20,10) NOT NULL DEFAULT 1,
    fx_rate_base_to_payment  NUMERIC(20,10) NOT NULL DEFAULT 1,
    fx_rate_base_to_settle   NUMERIC(20,10) NOT NULL DEFAULT 1,
    fx_source                VARCHAR(40)  NOT NULL DEFAULT 'MANUAL',
    fx_locked_at             TIMESTAMP,
    fx_locked_until          TIMESTAMP,
    tax_breakdown_json       TEXT,
    pricing_breakdown_json   TEXT,                        -- subtotal/surge/loyalty/etc as structured JSON
    billing_country_code     VARCHAR(8),
    billing_state_code       VARCHAR(16),
    billing_postal_code      VARCHAR(20),
    customer_type            VARCHAR(20),
    calculation_version      INTEGER      NOT NULL DEFAULT 1,
    created_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by               VARCHAR(120)
);
CREATE INDEX IF NOT EXISTS idx_price_snap_booking ON booking_price_snapshots(booking_ref);
CREATE INDEX IF NOT EXISTS idx_price_snap_binge   ON booking_price_snapshots(binge_id);

-- Snapshots are immutable: forbid UPDATE/DELETE at the DB layer.
CREATE OR REPLACE FUNCTION fn_block_snapshot_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'booking_price_snapshots is immutable; create a new row instead';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_snapshot_no_update ON booking_price_snapshots;
CREATE TRIGGER trg_snapshot_no_update
    BEFORE UPDATE OR DELETE ON booking_price_snapshots
    FOR EACH ROW EXECUTE FUNCTION fn_block_snapshot_mutation();

-- ── 6) fx_rate_locks (TTL-based) ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fx_rate_locks (
    id                       BIGSERIAL    PRIMARY KEY,
    lock_token               VARCHAR(64)  NOT NULL UNIQUE,    -- opaque, customer-facing
    customer_id              BIGINT,
    booking_ref              VARCHAR(20),
    from_currency            VARCHAR(8)   NOT NULL,
    to_currency              VARCHAR(8)   NOT NULL,
    fx_rate                  NUMERIC(20,10) NOT NULL,
    fx_source                VARCHAR(40)  NOT NULL DEFAULT 'MANUAL',
    base_amount              NUMERIC(14,4),                   -- amount the lock was quoted for
    converted_amount         NUMERIC(14,4),
    locked_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    locked_until             TIMESTAMP    NOT NULL,
    consumed_at              TIMESTAMP,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'  -- ACTIVE, CONSUMED, EXPIRED
);
CREATE INDEX IF NOT EXISTS idx_fx_lock_status ON fx_rate_locks(status, locked_until);
CREATE INDEX IF NOT EXISTS idx_fx_lock_token  ON fx_rate_locks(lock_token);

-- ── 7) invoices + invoice_lines ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS invoices (
    id                  BIGSERIAL    PRIMARY KEY,
    invoice_number      VARCHAR(40)  NOT NULL UNIQUE,
    booking_ref         VARCHAR(20)  NOT NULL,
    binge_id            BIGINT,
    customer_id         BIGINT,
    seller_legal_name   VARCHAR(200),
    seller_tax_id       VARCHAR(64),
    seller_address_line VARCHAR(400),
    buyer_name          VARCHAR(200),
    buyer_tax_id        VARCHAR(64),
    buyer_address_line  VARCHAR(400),
    buyer_country_code  VARCHAR(8),
    currency_code       VARCHAR(8)   NOT NULL,
    subtotal_amount     NUMERIC(14,4) NOT NULL,
    tax_amount          NUMERIC(14,4) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(14,4) NOT NULL,
    tax_breakdown_json  TEXT,
    snapshot_id         BIGINT,
    issued_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ISSUED', -- DRAFT, ISSUED, VOIDED
    pdf_url             VARCHAR(400),
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(120)
);
CREATE INDEX IF NOT EXISTS idx_invoices_booking ON invoices(booking_ref);
CREATE INDEX IF NOT EXISTS idx_invoices_customer ON invoices(customer_id);

CREATE TABLE IF NOT EXISTS invoice_lines (
    id              BIGSERIAL    PRIMARY KEY,
    invoice_id      BIGINT       NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    line_no         INTEGER      NOT NULL,
    description     VARCHAR(400) NOT NULL,
    quantity        NUMERIC(10,2) NOT NULL DEFAULT 1,
    unit_amount     NUMERIC(14,4) NOT NULL,
    line_total      NUMERIC(14,4) NOT NULL,
    tax_amount      NUMERIC(14,4) NOT NULL DEFAULT 0,
    tax_rate_bps    INTEGER,
    tax_type        VARCHAR(40)
);

-- ── 8) credit_notes ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS credit_notes (
    id                  BIGSERIAL    PRIMARY KEY,
    credit_note_number  VARCHAR(40)  NOT NULL UNIQUE,
    invoice_id          BIGINT       REFERENCES invoices(id) ON DELETE SET NULL,
    booking_ref         VARCHAR(20)  NOT NULL,
    refund_id           BIGINT,
    refunded_amount     NUMERIC(14,4) NOT NULL,
    refunded_tax        NUMERIC(14,4) NOT NULL DEFAULT 0,
    cancellation_fee    NUMERIC(14,4) NOT NULL DEFAULT 0,
    currency_code       VARCHAR(8)   NOT NULL,
    reason              VARCHAR(400),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ISSUED',
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(120)
);
CREATE INDEX IF NOT EXISTS idx_credit_notes_invoice ON credit_notes(invoice_id);
CREATE INDEX IF NOT EXISTS idx_credit_notes_booking ON credit_notes(booking_ref);

-- ── 9) ledger_entries (immutable, append-only) ──────────────────────────────
-- Double-entry semantics: each financial fact is stored as one or more rows.
-- Reversals are NEW rows with negated sign + reversal_of pointing back.
CREATE TABLE IF NOT EXISTS ledger_entries (
    id              BIGSERIAL    PRIMARY KEY,
    entry_uuid      VARCHAR(40)  NOT NULL UNIQUE,        -- idempotency key
    booking_ref     VARCHAR(20),
    binge_id        BIGINT,
    customer_id     BIGINT,
    payment_id      BIGINT,
    refund_id       BIGINT,
    invoice_id      BIGINT,
    credit_note_id  BIGINT,
    snapshot_id     BIGINT,
    entry_type      VARCHAR(40)  NOT NULL,               -- CHARGE, TAX_COLLECTED, PLATFORM_FEE, VENUE_PAYABLE,
                                                          -- LOYALTY_REDEMPTION, REFUND, TAX_REVERSAL,
                                                          -- CANCELLATION_FEE, FX_ADJUSTMENT
    direction       VARCHAR(8)   NOT NULL,               -- DEBIT, CREDIT
    amount          NUMERIC(14,4) NOT NULL,              -- always non-negative; sign comes from direction
    currency_code   VARCHAR(8)   NOT NULL,
    reversal_of     BIGINT       REFERENCES ledger_entries(id),
    description     VARCHAR(400),
    metadata_json   TEXT,
    occurred_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(120)
);
CREATE INDEX IF NOT EXISTS idx_ledger_booking  ON ledger_entries(booking_ref);
CREATE INDEX IF NOT EXISTS idx_ledger_customer ON ledger_entries(customer_id);
CREATE INDEX IF NOT EXISTS idx_ledger_type     ON ledger_entries(entry_type);
CREATE INDEX IF NOT EXISTS idx_ledger_occurred ON ledger_entries(occurred_at);

DROP TRIGGER IF EXISTS trg_ledger_no_update ON ledger_entries;
CREATE TRIGGER trg_ledger_no_update
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fn_block_snapshot_mutation();

-- ── 10) FK from bookings to snapshot + billing (deferred so backfill works) ─
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_booking_price_snapshot') THEN
        ALTER TABLE bookings
            ADD CONSTRAINT fk_booking_price_snapshot
            FOREIGN KEY (price_snapshot_id) REFERENCES booking_price_snapshots(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_booking_billing_address') THEN
        ALTER TABLE bookings
            ADD CONSTRAINT fk_booking_billing_address
            FOREIGN KEY (billing_address_id) REFERENCES billing_addresses(id);
    END IF;
END $$;
