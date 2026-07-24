-- Item 12: production-grade surge rules.
-- Beyond day-of-week + time window, real dynamic pricing needs:
--   date_from/date_to          - seasonal or event windows (New Year week, festival)
--   lead_time_max_hours        - last-minute premium (booking starts within X hours)
--   lead_time_min_hours        - early-bird window (booked at least X hours ahead;
--                                pair with multiplier < 1 for a discount)
--   occupancy_threshold_pct    - demand-based: applies only once the date is at
--                                least X% booked (Uber/hotel-style demand surge)
--   priority                   - deterministic winner among overlapping rules
--                                (lowest number wins; ties fall back to the
--                                strongest multiplier)
ALTER TABLE surge_pricing_rules
    ADD COLUMN IF NOT EXISTS date_from DATE,
    ADD COLUMN IF NOT EXISTS date_to DATE,
    ADD COLUMN IF NOT EXISTS lead_time_max_hours INT,
    ADD COLUMN IF NOT EXISTS lead_time_min_hours INT,
    ADD COLUMN IF NOT EXISTS occupancy_threshold_pct INT,
    ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 100;
