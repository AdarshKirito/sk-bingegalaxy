package com.skbingegalaxy.notification.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Tracks upcoming bookings so the reminder scheduler can fire
 * "your booking is tomorrow" and "1 hour before" notifications.
 */
@Document(collection = "booking_reminders")
@CompoundIndex(name = "idx_bookingRef_type", def = "{'bookingRef': 1, 'reminderType': 1}", unique = true)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BookingReminder {

    @Id
    private String id;

    @Indexed
    private String bookingRef;

    private String recipientEmail;
    private String recipientPhone;
    private String recipientName;
    private String eventTypeName;

    private LocalDate bookingDate;
    private LocalTime startTime;
    private int durationHours;

    /** "DAY_BEFORE" or "ONE_HOUR_BEFORE" */
    private String reminderType;

    /** When this reminder should fire (computed at creation). */
    @Indexed
    private LocalDateTime fireAt;

    /** True once the reminder has been dispatched. */
    @Builder.Default
    private boolean fired = false;

    /** True if the booking was cancelled (skip reminder). */
    @Builder.Default
    private boolean cancelled = false;
}
