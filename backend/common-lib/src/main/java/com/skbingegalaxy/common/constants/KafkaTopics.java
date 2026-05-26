package com.skbingegalaxy.common.constants;

/**
 * Kafka topic names shared across producers and consumers.
 */
public final class KafkaTopics {
    private KafkaTopics() {}

    public static final String BOOKING_CREATED  = "booking.created";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String BOOKING_RESCHEDULED = "booking.rescheduled";
    public static final String BOOKING_TRANSFERRED = "booking.transferred";
    public static final String BOOKING_CHECKED_IN = "booking.checked-in";
    public static final String BOOKING_COMPLETED  = "booking.completed";
    public static final String BOOKING_CASH_PAYMENT = "booking.cash-payment";
    public static final String WAITLIST_PROMOTED = "waitlist.promoted";
    public static final String PAYMENT_SUCCESS   = "payment.success";
    public static final String PAYMENT_FAILED    = "payment.failed";
    public static final String PAYMENT_REFUNDED  = "payment.refunded";
    public static final String NOTIFICATION_SEND = "notification.send";
    public static final String USER_REGISTERED   = "user.registered";
    public static final String PASSWORD_RESET    = "password.reset";

    // V56/V57: admin-surface lifecycle events. Payload: AdminLifecycleEvent.
    public static final String ROOM_APPROVED  = "room.approved";
    public static final String ROOM_REJECTED  = "room.rejected";
    public static final String ROOM_BLOCKED   = "room.blocked";
    public static final String ROOM_UNBLOCKED = "room.unblocked";
}
