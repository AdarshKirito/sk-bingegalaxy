-- =============================================================================
-- V52 — Relax legacy NOT-NULL columns now superseded by the entity model.
--
-- Background
-- ----------
-- V39 created several columns as NOT NULL that the current JPA entities no
-- longer map (the entities have evolved to use newer canonical fields, e.g.
-- `sort_order` instead of `line_no`, and dropped `settlement_total` from
-- `BookingPriceSnapshot` as redundant with `payment_total`). Hibernate
-- therefore omits these columns from INSERT statements, and the database
-- aborts with `null value in column "<col>" violates not-null constraint`.
--
-- Affected columns (DB NOT NULL, entity unmapped):
--   * invoice_lines.line_no                  — superseded by sort_order
--   * booking_price_snapshots.settlement_total
--
-- Fix
-- ---
-- Drop the NOT NULL constraint on each. Columns are kept for read-side
-- backwards compatibility (legacy reports / SQL consumers). For
-- `invoice_lines.line_no` an INSERT trigger backfills the value from
-- `sort_order` so legacy consumers still see a monotonic ordering.
-- =============================================================================

-- 1) invoice_lines.line_no -----------------------------------------------------
ALTER TABLE invoice_lines ALTER COLUMN line_no DROP NOT NULL;

CREATE OR REPLACE FUNCTION fn_invoice_lines_default_line_no() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.line_no IS NULL THEN
        NEW.line_no := COALESCE(NEW.sort_order, 0);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_invoice_lines_default_line_no ON invoice_lines;
CREATE TRIGGER trg_invoice_lines_default_line_no
    BEFORE INSERT ON invoice_lines
    FOR EACH ROW EXECUTE FUNCTION fn_invoice_lines_default_line_no();

-- 2) booking_price_snapshots.settlement_total ---------------------------------
ALTER TABLE booking_price_snapshots ALTER COLUMN settlement_total DROP NOT NULL;
