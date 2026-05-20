-- V46: Add Kafka event-envelope metadata columns to the outbox.
--
-- Background:
--   The outbox row used to carry just (topic, aggregate_key, payload). Adding
--   eventId/eventVersion/eventType to the *payload* (via EventEnvelope) is
--   enough for consumers, but lifting the same metadata into columns lets us:
--     1. dedupe at insert time (UNIQUE on event_id),
--     2. expose Kafka record headers without re-parsing JSON,
--     3. operate (filter/replay/audit) on a topic-shape across millions of
--        rows without scanning the TEXT payload.
--
-- All columns are nullable for backward compatibility — existing rows
-- written before this migration keep working untouched. Producers updated
-- via BookingEventPublisher always populate them; the OutboxPublisher
-- backfills nullable columns by inspecting the payload before sending.

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS event_id        VARCHAR(64),
    ADD COLUMN IF NOT EXISTS event_type      VARCHAR(80),
    ADD COLUMN IF NOT EXISTS event_version   INTEGER,
    ADD COLUMN IF NOT EXISTS occurred_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS correlation_id  VARCHAR(64);

-- Idempotency: same eventId must not be written twice by retried HTTP requests.
CREATE UNIQUE INDEX IF NOT EXISTS ux_outbox_event_id ON outbox_event (event_id);

-- Operational scan helpers.
CREATE INDEX IF NOT EXISTS idx_outbox_event_type   ON outbox_event (event_type);
CREATE INDEX IF NOT EXISTS idx_outbox_correlation  ON outbox_event (correlation_id);
