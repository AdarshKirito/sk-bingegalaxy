-- Fix implausibly low seeded venue room capacities.
-- Original seed had 3/2/1 which doesn't match realistic theater capacities.
-- Only updates rooms that are still at the original seed values so customer-edited
-- capacities are not overwritten.
UPDATE venue_rooms SET capacity = 25 WHERE room_type = 'MAIN_HALL'    AND capacity = 3;
UPDATE venue_rooms SET capacity = 12 WHERE room_type = 'PRIVATE_ROOM' AND capacity = 2;
UPDATE venue_rooms SET capacity = 8  WHERE room_type = 'VIP_LOUNGE'   AND capacity = 1;
