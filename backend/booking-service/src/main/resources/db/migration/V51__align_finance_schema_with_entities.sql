-- =============================================================================
-- V51 — Reconcile finance schema (V39) with the JPA entities.
--
-- Background
-- ----------
-- V39 introduced the production-grade finance tables (booking_price_snapshots,
-- billing_addresses, fx_rate_locks, invoices, invoice_lines, credit_notes,
-- ledger_entries). The matching JPA entities subsequently evolved (more
-- semantic column names, new fields) but no follow-up migration was shipped.
-- Hibernate's schema-validation therefore aborts at startup with a series
-- of `missing column` errors.
--
-- This migration brings the database in line with the entity model:
--   * BookingPriceSnapshot       → renames + adds missing columns
--   * Invoice                    → renames + adds missing columns
--   * InvoiceLine                → adds line_type / amount / sort_order
--   * CreditNote                 → renames refunded_* → amount / tax_amount,
--                                   adds metadata_json
--   * LedgerEntry                → renames created_by → recorded_by,
--                                   adds fx_rate_to_base / amount_in_base
--
-- All operations are idempotent so the migration is safe to apply against
-- environments where parts of the rename were already performed manually.
-- =============================================================================

-- ── Helper: rename a column only if old exists and new does not. ────────────
CREATE OR REPLACE FUNCTION _v51_rename_col(p_table TEXT, p_old TEXT, p_new TEXT)
RETURNS VOID AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = p_table AND column_name = p_old
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = p_table AND column_name = p_new
    ) THEN
        EXECUTE format('ALTER TABLE %I RENAME COLUMN %I TO %I', p_table, p_old, p_new);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) booking_price_snapshots
-- ─────────────────────────────────────────────────────────────────────────────
SELECT _v51_rename_col('booking_price_snapshots', 'subtotal_amount',         'subtotal_base');
SELECT _v51_rename_col('booking_price_snapshots', 'surge_amount',            'surge_amount_base');
SELECT _v51_rename_col('booking_price_snapshots', 'coupon_discount',         'discount_amount_base');
SELECT _v51_rename_col('booking_price_snapshots', 'loyalty_discount',        'loyalty_redemption_base');
SELECT _v51_rename_col('booking_price_snapshots', 'platform_fee',            'platform_fee_base');
SELECT _v51_rename_col('booking_price_snapshots', 'tax_amount',              'tax_amount_base');
SELECT _v51_rename_col('booking_price_snapshots', 'final_total_base',        'total_base');
SELECT _v51_rename_col('booking_price_snapshots', 'fx_rate_base_to_display', 'fx_rate_display');
SELECT _v51_rename_col('booking_price_snapshots', 'fx_rate_base_to_payment', 'fx_rate_payment');
SELECT _v51_rename_col('booking_price_snapshots', 'fx_rate_base_to_settle',  'fx_rate_settlement');
SELECT _v51_rename_col('booking_price_snapshots', 'billing_country_code',    'billing_country');
SELECT _v51_rename_col('booking_price_snapshots', 'billing_state_code',      'billing_state');

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) invoices
-- ─────────────────────────────────────────────────────────────────────────────
SELECT _v51_rename_col('invoices', 'subtotal_amount', 'subtotal');
SELECT _v51_rename_col('invoices', 'tax_amount',      'tax_total');
SELECT _v51_rename_col('invoices', 'total_amount',    'grand_total');

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS billing_address_id BIGINT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS discount_total     NUMERIC(14,4) NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS due_at             TIMESTAMP;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS metadata_json      TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS updated_at         TIMESTAMP NOT NULL DEFAULT NOW();

-- Status field: V39 declared length 20, entity wants 30. Widen safely.
ALTER TABLE invoices ALTER COLUMN status TYPE VARCHAR(30);

-- Optional FK to billing_addresses (mirrors the bookings.billing_address_id FK
-- already added in V39). NULL is allowed because B2C bookings may skip it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_invoices_billing_address') THEN
        ALTER TABLE invoices
            ADD CONSTRAINT fk_invoices_billing_address
            FOREIGN KEY (billing_address_id) REFERENCES billing_addresses(id);
    END IF;
END$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) invoice_lines
-- ─────────────────────────────────────────────────────────────────────────────
SELECT _v51_rename_col('invoice_lines', 'line_total', 'amount');

ALTER TABLE invoice_lines ADD COLUMN IF NOT EXISTS line_type  VARCHAR(30) NOT NULL DEFAULT 'CHARGE';
ALTER TABLE invoice_lines ADD COLUMN IF NOT EXISTS sort_order INTEGER     NOT NULL DEFAULT 0;

-- Backfill sort_order from the legacy `line_no` column when it was carried over.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'invoice_lines' AND column_name = 'line_no'
    ) THEN
        UPDATE invoice_lines SET sort_order = COALESCE(line_no, 0) WHERE sort_order = 0;
    END IF;
END$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) credit_notes
-- ─────────────────────────────────────────────────────────────────────────────
SELECT _v51_rename_col('credit_notes', 'refunded_amount', 'amount');
SELECT _v51_rename_col('credit_notes', 'refunded_tax',    'tax_amount');

ALTER TABLE credit_notes ADD COLUMN IF NOT EXISTS metadata_json TEXT;

-- Reason: V39 stored a free-text 400-char column; entity now stores an
-- enum value (max 40 chars). Truncate then narrow.
UPDATE credit_notes SET reason = LEFT(reason, 40) WHERE reason IS NOT NULL;
ALTER TABLE credit_notes ALTER COLUMN reason TYPE VARCHAR(40);
UPDATE credit_notes SET reason = 'OTHER' WHERE reason IS NULL;
ALTER TABLE credit_notes ALTER COLUMN reason SET NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5) ledger_entries
-- ─────────────────────────────────────────────────────────────────────────────
SELECT _v51_rename_col('ledger_entries', 'created_by', 'recorded_by');

ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS fx_rate_to_base NUMERIC(20,10);
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS amount_in_base  NUMERIC(14,4);

-- Drop the helper so it doesn't pollute the schema namespace.
DROP FUNCTION IF EXISTS _v51_rename_col(TEXT, TEXT, TEXT);
