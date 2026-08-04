-- V77 — DATA-007: drop prefix-redundant single-/short-column indexes on the hot
-- `bookings` table to cut write amplification (every INSERT/UPDATE maintains every
-- index). Each dropped index is a STRICT LEADING-COLUMN PREFIX of an existing
-- composite, so PostgreSQL already serves any query the dropped index could serve
-- via the composite's leading-prefix scan — this is structural redundancy, not a
-- usage-dependent guess:
--
--   idx_booking_date       (booking_date)             ⊂ idx_bookings_date_status        (booking_date, status)          [V62]
--   idx_booking_customer   (customer_id)              ⊂ idx_booking_customer_status     (customer_id, status)           [V15]
--                                                      (also ⊂ idx_bookings_customer_date (customer_id, booking_date)   [V62])
--   idx_booking_binge_date (binge_id, booking_date)   ⊂ idx_bookings_binge_date_status  (binge_id, booking_date, status)[V62]
--
-- Safety notes:
--  * A composite (a,b[,c]) supports equality/range predicates and ORDER BY on its
--    leading prefix (a) and (a,b) exactly as a dedicated (a) / (a,b) index would,
--    so no query plan loses an access path. The only thing the narrower index gave
--    was a marginally smaller scan; the write cost on a hot table outweighs it.
--  * DROP INDEX takes a brief ACCESS EXCLUSIVE lock but is a near-instant metadata
--    operation. IF EXISTS keeps this idempotent and safe to re-run.
--  * Fully reversible — if a future workload proves a standalone index is warranted
--    (e.g. an index-only scan on a single column), recreate it explicitly.
--
-- Leaves untouched: idx_booking_ref (unique), idx_booking_status (status alone — not
-- a prefix of any composite that leads with status), and all partial/functional
-- indexes, none of which are prefix-redundant.

DROP INDEX IF EXISTS idx_booking_date;
DROP INDEX IF EXISTS idx_booking_customer;
DROP INDEX IF EXISTS idx_booking_binge_date;

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: dropping indexes IS the purpose of this migration — each one is a strict
-- prefix of a composite index that remains, so no query loses its access path. Indexes
-- hold no data and are rebuildable from the table, which makes this the cheapest class
-- of destructive change to reverse.
