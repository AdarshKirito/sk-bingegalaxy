-- V43: Production-grade audit fields on booking_event_log.
-- Adds reason, ip_address, user_agent, binge_id so admin actions
-- (cancel/refund/reschedule/transfer/check-in undo/price override/
-- manual confirmation) capture WHO + WHAT + WHY + WHERE.

ALTER TABLE booking_event_log
    ADD COLUMN IF NOT EXISTS reason       VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS ip_address   VARCHAR(45),
    ADD COLUMN IF NOT EXISTS user_agent   VARCHAR(500),
    ADD COLUMN IF NOT EXISTS binge_id     BIGINT;

CREATE INDEX IF NOT EXISTS idx_bel_binge_id   ON booking_event_log(binge_id);
CREATE INDEX IF NOT EXISTS idx_bel_actor_role ON booking_event_log(triggered_by_role);
