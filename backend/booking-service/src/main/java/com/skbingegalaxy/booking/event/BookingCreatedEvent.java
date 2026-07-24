package com.skbingegalaxy.booking.event;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * In-process Spring event fired by {@code BookingService} after a customer
 * booking row is created. Same pattern (and rationale) as
 * {@link BookingCompletedEvent}: {@code @TransactionalEventListener
 * (phase = AFTER_COMMIT)} listeners fire only if the booking commit succeeded.
 *
 * <p>Current consumer: {@code WaitlistService} — closes the OFFERED → BOOKED
 * loop when the booking matches an outstanding waitlist offer, and releases
 * the offer's slot hold.</p>
 */
public record BookingCreatedEvent(
        Long bookingId,
        String bookingRef,
        Long customerId,
        Long bingeId,
        LocalDate bookingDate,
        LocalTime startTime
) { }
