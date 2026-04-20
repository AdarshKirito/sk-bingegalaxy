-- V13: Add FK constraint for venue_room_id on bookings

ALTER TABLE bookings
    ADD CONSTRAINT fk_bookings_venue_room
    FOREIGN KEY (venue_room_id) REFERENCES venue_rooms(id)
    ON DELETE SET NULL;
