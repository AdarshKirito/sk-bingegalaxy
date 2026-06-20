-- V64: Add geo-coordinates to binges for location-aware "nearest venues" discovery.
--
-- latitude/longitude are the WGS-84 decimal-degree coordinates of the venue, used by
-- the public /api/v1/bookings/binges/nearby endpoint to rank venues by distance from
-- the customer's current location (browser Geolocation API). Both are nullable: legacy
-- venues without coordinates simply never surface in proximity results until an admin
-- sets them, and the alphabetical listing remains the fallback.
--
-- A composite index on (latitude, longitude) lets the bounding-box pre-filter in
-- BingeRepository.findNearbyCandidates use an index scan instead of a full table scan
-- before the exact Haversine refinement runs in the service layer. Partial (WHERE NOT
-- NULL) so it stays small while most venues are still un-geocoded.

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS latitude  DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;

-- Guard against impossible coordinates at the storage layer (defence in depth; the
-- DTO/service also validate). Allows NULL (un-geocoded venues). Postgres has no
-- "ADD CONSTRAINT IF NOT EXISTS", so guard on pg_constraint to stay re-runnable.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_binges_latitude') THEN
        ALTER TABLE binges
            ADD CONSTRAINT chk_binges_latitude  CHECK (latitude  IS NULL OR (latitude  BETWEEN -90  AND 90));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_binges_longitude') THEN
        ALTER TABLE binges
            ADD CONSTRAINT chk_binges_longitude CHECK (longitude IS NULL OR (longitude BETWEEN -180 AND 180));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_binges_lat_lng
    ON binges (latitude, longitude)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;
