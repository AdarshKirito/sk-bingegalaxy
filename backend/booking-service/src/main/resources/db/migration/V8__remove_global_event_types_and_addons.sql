-- Remove global (non-binge-scoped) event types and add-ons.
-- Each binge admin now creates their own from scratch.
-- Only delete rows where binge_id IS NULL (the V1 seed data).

-- 1. Remove booking-level references to global add-ons / event types
DELETE FROM booking_add_ons
 WHERE add_on_id IN (SELECT id FROM add_ons WHERE binge_id IS NULL)
    OR booking_id IN (SELECT id FROM bookings WHERE event_type_id IN (SELECT id FROM event_types WHERE binge_id IS NULL));

DELETE FROM bookings WHERE event_type_id IN (SELECT id FROM event_types WHERE binge_id IS NULL);

-- 2. Remove pricing references
DELETE FROM rate_code_addon_pricing  WHERE add_on_id    IN (SELECT id FROM add_ons     WHERE binge_id IS NULL);
DELETE FROM rate_code_event_pricing  WHERE event_type_id IN (SELECT id FROM event_types WHERE binge_id IS NULL);
DELETE FROM customer_addon_pricing   WHERE add_on_id    IN (SELECT id FROM add_ons     WHERE binge_id IS NULL);
DELETE FROM customer_event_pricing   WHERE event_type_id IN (SELECT id FROM event_types WHERE binge_id IS NULL);

-- 3. Remove images
DELETE FROM add_on_images      WHERE add_on_id    IN (SELECT id FROM add_ons     WHERE binge_id IS NULL);
DELETE FROM event_type_images  WHERE event_type_id IN (SELECT id FROM event_types WHERE binge_id IS NULL);

-- 4. Remove the global entities themselves
DELETE FROM add_ons     WHERE binge_id IS NULL;
DELETE FROM event_types WHERE binge_id IS NULL;
