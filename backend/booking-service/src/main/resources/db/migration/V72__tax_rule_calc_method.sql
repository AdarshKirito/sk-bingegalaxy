-- Item 6: production-grade tax rules.
-- Percentage-only rules can't express real-world lodging taxes like a flat
-- per-reservation fee or a per-hour occupancy charge (e.g. "Cook County
-- amusement fee: $1.50 per reservation", "City occupancy: $0.50 per hour").
-- calc_method:
--   PERCENT           - rate_bps applied to the applies_to base (existing behavior)
--   FLAT_PER_BOOKING  - flat_amount charged once per reservation
--   FLAT_PER_HOUR     - flat_amount x booked hours (rounded up)
ALTER TABLE tax_rules
    ADD COLUMN IF NOT EXISTS calc_method VARCHAR(20) NOT NULL DEFAULT 'PERCENT',
    ADD COLUMN IF NOT EXISTS flat_amount NUMERIC(12, 2);
