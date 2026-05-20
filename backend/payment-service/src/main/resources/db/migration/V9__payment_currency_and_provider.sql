-- ============================================================================
-- V9 — Production-grade currency, FX-locking, provider abstraction
-- ----------------------------------------------------------------------------
-- Adds the columns the Payment entity needs to record:
--   * which currency was actually charged (paymentCurrencyCode)
--   * which currency the platform is settled in (settlementCurrencyCode)
--   * the FX rate locked in at charge time (fxRateAtPayment)
--   * which gateway/provider handled the charge (providerName)
--   * whether the customer used a pre-locked rate (fxLockId, fxLockedUntil)
--
-- Backward compatibility:
--   * The legacy `currency` column is kept (now serves as the display currency).
--   * The legacy `tax` column is kept (numeric tax amount); a new
--     tax_amount column duplicates it semantically so newer code can use a
--     consistent name. The trigger keeps them in sync on INSERT/UPDATE.
-- ============================================================================

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS payment_currency_code     VARCHAR(8),
    ADD COLUMN IF NOT EXISTS display_currency_code     VARCHAR(8),
    ADD COLUMN IF NOT EXISTS settlement_currency_code  VARCHAR(8),
    ADD COLUMN IF NOT EXISTS fx_rate_at_payment        NUMERIC(20, 10),
    ADD COLUMN IF NOT EXISTS fx_locked_until           TIMESTAMP,
    ADD COLUMN IF NOT EXISTS fx_lock_id                VARCHAR(64),
    ADD COLUMN IF NOT EXISTS provider_name             VARCHAR(40),
    ADD COLUMN IF NOT EXISTS tax_amount                NUMERIC(14, 4),
    ADD COLUMN IF NOT EXISTS amount_in_settlement      NUMERIC(14, 4);

-- Backfill currency-role columns for existing rows so reporting queries
-- never see NULLs on legacy data.
UPDATE payments
SET    payment_currency_code     = COALESCE(payment_currency_code, currency, 'INR'),
       display_currency_code     = COALESCE(display_currency_code, currency, 'INR'),
       settlement_currency_code  = COALESCE(settlement_currency_code, 'INR'),
       provider_name             = COALESCE(provider_name, 'razorpay'),
       tax_amount                = COALESCE(tax_amount, tax)
WHERE  payment_currency_code IS NULL
   OR  display_currency_code IS NULL
   OR  settlement_currency_code IS NULL
   OR  provider_name IS NULL
   OR  (tax_amount IS NULL AND tax IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_payment_provider           ON payments(provider_name);
CREATE INDEX IF NOT EXISTS idx_payment_payment_currency   ON payments(payment_currency_code);
CREATE INDEX IF NOT EXISTS idx_payment_fx_lock_id         ON payments(fx_lock_id);
