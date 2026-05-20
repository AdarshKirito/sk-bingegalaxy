-- ════════════════════════════════════════════════════════════════════════════
-- Taxes & Multi-currency support
-- ════════════════════════════════════════════════════════════════════════════
-- Strategy:
--   • Base currency for canonical storage = INR (rate 1.0).
--   • Non-base currencies are configured rows in `currency_rates`.
--   • Booking amounts are stored in BASE currency (INR). Display layer converts
--     using `currency_rates` at view time. The booking row also captures the
--     currency the customer was *quoted in* and the FX rate at booking time
--     so that historical receipts/invoices stay reproducible even after rates
--     change. Payment-service charges in the quoted currency via Razorpay.
--   • Tax is computed in INR on the post-surge / post-loyalty subtotal
--     (configurable per rule via `applies_to`). Breakdown is persisted as JSON
--     for invoices and audit.
-- ════════════════════════════════════════════════════════════════════════════

-- ── 1. Booking financial columns ─────────────────────────────────────────────
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS subtotal_amount     NUMERIC(12,2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS tax_amount          NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS tax_breakdown_json  TEXT;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS currency_code       VARCHAR(8)    NOT NULL DEFAULT 'INR';
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS display_amount      NUMERIC(14,2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fx_rate             NUMERIC(18,8) NOT NULL DEFAULT 1.0;

-- Backfill subtotal_amount = total_amount for existing rows (no tax was charged).
UPDATE bookings SET subtotal_amount = total_amount WHERE subtotal_amount IS NULL;
ALTER TABLE bookings ALTER COLUMN subtotal_amount SET NOT NULL;

-- ── 2. Tax rules ─────────────────────────────────────────────────────────────
-- A row is *active* when active=true. Resolution order at booking time:
--   1. binge_id = current binge (highest priority)
--   2. binge_id IS NULL (platform default)
-- Within each scope rules are ordered by `priority ASC`. All matching rules are
-- summed (e.g. CGST 9% + SGST 9% = 18%).
CREATE TABLE IF NOT EXISTS tax_rules (
    id              BIGSERIAL PRIMARY KEY,
    binge_id        BIGINT,                       -- NULL = global / platform default
    name            VARCHAR(120)  NOT NULL,
    description     VARCHAR(500),
    rate_bps        INTEGER       NOT NULL,       -- basis points (500 = 5%, 1800 = 18%)
    applies_to      VARCHAR(20)   NOT NULL DEFAULT 'TOTAL', -- TOTAL | BASE | ADDONS | GUEST
    inclusive       BOOLEAN       NOT NULL DEFAULT FALSE,   -- true = price already includes tax
    country_code    VARCHAR(8),                   -- ISO-3166-1 alpha-2; NULL = applies anywhere
    region_code     VARCHAR(16),                  -- e.g. state code; NULL = all regions
    priority        INTEGER       NOT NULL DEFAULT 100,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tax_rate_non_negative CHECK (rate_bps >= 0 AND rate_bps <= 100000),
    CONSTRAINT chk_tax_applies_to CHECK (applies_to IN ('TOTAL','BASE','ADDONS','GUEST'))
);

CREATE INDEX IF NOT EXISTS idx_tax_rules_binge      ON tax_rules(binge_id);
CREATE INDEX IF NOT EXISTS idx_tax_rules_active     ON tax_rules(active);
CREATE INDEX IF NOT EXISTS idx_tax_rules_priority   ON tax_rules(priority);

-- Seed sensible default — Indian GST 18%, platform-wide. Admins can override per binge.
INSERT INTO tax_rules (binge_id, name, description, rate_bps, applies_to, inclusive, country_code, priority, active)
VALUES (NULL, 'GST', 'Goods & Services Tax (default)', 1800, 'TOTAL', FALSE, 'IN', 100, TRUE);

-- ── 3. Currency rates ────────────────────────────────────────────────────────
-- Stores the FX rate of 1 INR -> 1 unit_of_currency. So to convert an INR
-- amount to display currency, multiply by `rate_to_base`. To convert back,
-- divide.
CREATE TABLE IF NOT EXISTS currency_rates (
    code            VARCHAR(8)    PRIMARY KEY,    -- ISO-4217 (USD, EUR, INR, …)
    name            VARCHAR(80)   NOT NULL,
    symbol          VARCHAR(8)    NOT NULL,
    rate_to_base    NUMERIC(18,8) NOT NULL,       -- 1 INR == rate_to_base * 1 of this currency
    decimal_digits  INTEGER       NOT NULL DEFAULT 2,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    is_base         BOOLEAN       NOT NULL DEFAULT FALSE,
    last_updated    TIMESTAMP     NOT NULL DEFAULT NOW(),
    manual_override BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_currency_rate_positive CHECK (rate_to_base > 0),
    CONSTRAINT chk_currency_decimals      CHECK (decimal_digits >= 0 AND decimal_digits <= 6)
);

-- Seed 12 widely-used currencies with approximate rates (April 2026 ballpark).
-- Admins can refresh from a live FX provider via the admin UI; values here
-- are baseline so the system runs out-of-the-box even offline.
INSERT INTO currency_rates (code, name, symbol, rate_to_base, decimal_digits, active, is_base, manual_override) VALUES
    ('INR', 'Indian Rupee',       '₹',  1.00000000, 2, TRUE,  TRUE,  TRUE),
    ('USD', 'US Dollar',          '$',  0.01200000, 2, TRUE,  FALSE, TRUE),
    ('EUR', 'Euro',               '€',  0.01100000, 2, TRUE,  FALSE, TRUE),
    ('GBP', 'British Pound',      '£',  0.00940000, 2, TRUE,  FALSE, TRUE),
    ('AED', 'UAE Dirham',         'د.إ',0.04400000, 2, TRUE,  FALSE, TRUE),
    ('SGD', 'Singapore Dollar',   'S$', 0.01620000, 2, TRUE,  FALSE, TRUE),
    ('AUD', 'Australian Dollar',  'A$', 0.01840000, 2, TRUE,  FALSE, TRUE),
    ('CAD', 'Canadian Dollar',    'C$', 0.01640000, 2, TRUE,  FALSE, TRUE),
    ('JPY', 'Japanese Yen',       '¥',  1.82000000, 0, TRUE,  FALSE, TRUE),
    ('CNY', 'Chinese Yuan',       '¥',  0.08700000, 2, TRUE,  FALSE, TRUE),
    ('CHF', 'Swiss Franc',        'Fr', 0.01060000, 2, TRUE,  FALSE, TRUE),
    ('SAR', 'Saudi Riyal',        '﷼',  0.04500000, 2, TRUE,  FALSE, TRUE)
ON CONFLICT (code) DO NOTHING;
