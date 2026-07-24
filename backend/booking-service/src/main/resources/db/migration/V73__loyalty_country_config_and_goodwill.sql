-- Item 2: country-aware loyalty economics + binge goodwill budgets.

-- 1) Per-country earn/redeem defaults.
-- WHY: the auto-seeder used to give EVERY binge "10 pts per 1.00 unit of its
-- own currency" — so a US binge (USD) earned ~83x richer per real value than an
-- Indian binge (INR). This table lets the super admin define, per country, how
-- much local currency earns points and how many points a local currency unit
-- is worth at redemption — keeping one platform-wide point value.
CREATE TABLE loyalty_country_earn_config (
    country_iso2            VARCHAR(2)  PRIMARY KEY,
    currency_code           VARCHAR(8)  NOT NULL,
    -- Earn: points_numerator points per amount_denominator units of local currency.
    points_numerator        BIGINT      NOT NULL,
    amount_denominator      NUMERIC(12, 2) NOT NULL,
    -- Redeem: points needed per 1 unit of local currency.
    points_per_currency_unit BIGINT     NOT NULL,
    min_redemption_points   BIGINT      NOT NULL DEFAULT 100,
    max_redemption_percent  NUMERIC(5, 2) NOT NULL DEFAULT 50.00,
    active                  BOOLEAN     NOT NULL DEFAULT TRUE,
    notes                   VARCHAR(300),
    updated_by_admin_id     BIGINT,
    created_at              TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Rough purchasing-parity defaults anchored to the INR baseline
-- (10 pts / Rs 1 earn; 100 pts = Rs 1 redeem). Super admin tunes these in the
-- Loyalty Center; countries without a row fall back to the INR baseline.
INSERT INTO loyalty_country_earn_config
    (country_iso2, currency_code, points_numerator, amount_denominator,
     points_per_currency_unit, min_redemption_points, max_redemption_percent, notes)
VALUES
    ('IN', 'INR', 10,  1.00,   100,  100, 50.00, 'Platform baseline'),
    ('US', 'USD', 800, 1.00,  8000,  100, 50.00, 'Parity ~ Rs 83 / USD'),
    ('GB', 'GBP', 1000, 1.00, 10000, 100, 50.00, 'Parity ~ Rs 105 / GBP'),
    ('AE', 'AED', 220, 1.00,  2200,  100, 50.00, 'Parity ~ Rs 22 / AED'),
    ('SG', 'SGD', 600, 1.00,  6000,  100, 50.00, 'Parity ~ Rs 62 / SGD'),
    ('AU', 'AUD', 550, 1.00,  5500,  100, 50.00, 'Parity ~ Rs 55 / AUD'),
    ('CA', 'CAD', 600, 1.00,  6000,  100, 50.00, 'Parity ~ Rs 60 / CAD'),
    ('DE', 'EUR', 900, 1.00,  9000,  100, 50.00, 'Parity ~ Rs 90 / EUR'),
    ('FR', 'EUR', 900, 1.00,  9000,  100, 50.00, 'Parity ~ Rs 90 / EUR'),
    ('JP', 'JPY', 6,   1.00,    60,  100, 50.00, 'Parity ~ Rs 0.55 / JPY');

-- 2) Per-binge goodwill budget: lets a binge admin credit points to a customer
-- after a bad experience (capped per month, super-admin controlled).
ALTER TABLE loyalty_binge_binding
    ADD COLUMN IF NOT EXISTS goodwill_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS goodwill_monthly_cap_points BIGINT NOT NULL DEFAULT 0;
