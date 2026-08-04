-- Minimal stand-ins for the tables V81 touches, shaped like the real schema in
-- the columns the migration and the trigger actually read.
CREATE TABLE binges (
    id BIGSERIAL PRIMARY KEY,
    max_concurrent_bookings INTEGER
);

CREATE TABLE venue_rooms (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT NOT NULL,
    capacity INTEGER NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL DEFAULT 'APPROVED'
);

CREATE TABLE event_types (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT
);

CREATE TABLE bookings (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT,
    venue_room_id BIGINT,
    booking_date DATE NOT NULL,
    start_time TIME NOT NULL,
    duration_hours INTEGER NOT NULL DEFAULT 0,
    duration_minutes INTEGER,
    actual_used_minutes INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
);

CREATE TABLE slot_holds (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT NOT NULL,
    booking_date DATE NOT NULL,
    start_time TIME NOT NULL,
    duration_minutes INTEGER NOT NULL
);

-- Pre-existing V75 trigger, so V81's CREATE OR REPLACE is exercised as a real replace.
CREATE OR REPLACE FUNCTION booking_occupancy_backstop() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
CREATE TRIGGER trg_booking_occupancy_backstop
    AFTER INSERT OR UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION booking_occupancy_backstop();

-- Legacy rows the V81 backfill must repair: duration_minutes NULL / 0 with a
-- real duration only expressible via duration_hours.
INSERT INTO binges (id, max_concurrent_bookings) VALUES (1, 1);
INSERT INTO venue_rooms (id, binge_id, capacity) VALUES (1, 1, 1), (2, 1, 2);
INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time, duration_hours, duration_minutes, status)
VALUES (1, NULL, DATE '2026-09-01', TIME '10:00', 2, NULL,  'CONFIRMED'),
       (1, NULL, DATE '2026-09-01', TIME '14:00', 3, 0,     'CONFIRMED');
