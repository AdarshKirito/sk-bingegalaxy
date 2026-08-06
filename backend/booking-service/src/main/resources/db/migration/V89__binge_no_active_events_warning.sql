-- V89 — surface the OTHER way a venue goes silently invisible.
--
-- THE GAP -------------------------------------------------------------------
-- V87 closed the case where a venue with event types was wrongly auto-paused: the
-- sweep now corroborates against event_types and a trigger stamps
-- first_event_created_at on any insert. Both are about the EXISTENCE of event types.
--
-- Customer discovery does not care about existence. findCustomerVisibleBinges()
-- requires at least one ACTIVE event type. So a venue can hold thirteen event types
-- with active = false and be:
--
--   * exempt from the grace period      (rows exist, so the flag is stamped),
--   * shown as active in the admin console (binges.active is true),
--   * and completely absent from the customer site.
--
-- That is the same failure the V87 incident was about — a venue nobody can book,
-- reported nowhere — reached through a different flag. The venue only finds out when
-- they ask why bookings stopped.
--
-- WHAT THIS DOES NOT DO -----------------------------------------------------
-- It does not auto-pause. Deactivating every event type is a legitimate thing for an
-- operator to do (seasonal closure, a catalogue rebuild), and pausing the venue on top
-- of it would be the platform overriding a deliberate choice — the very behaviour the
-- V87 incident made everyone rightly wary of. The sweep only NOTIFIES, once per
-- episode, and this column is what makes "once" true.

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS no_active_events_warned_at TIMESTAMP;

COMMENT ON COLUMN binges.no_active_events_warned_at IS
    'When the admin was last told this approved venue has no ACTIVE event type and is therefore absent from customer discovery. Cleared by BingeService.enforceGracePeriod once an active event type exists again, so a recurrence warns again. Never causes a pause — see V89.';
