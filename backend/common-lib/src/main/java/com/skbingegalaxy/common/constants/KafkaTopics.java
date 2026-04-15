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
    public static final String BOOKING_CASH_PAYMENT = "booking.cash-payment";
    public static final String PAYMENT_SUCCESS   = "payment.success";
    public static final String PAYMENT_FAILED    = "payment.failed";
    public static final String PAYMENT_REFUNDED  = "payment.refunded";
    public static final String NOTIFICATION_SEND = "notification.send";
    public static final String USER_REGISTERED   = "user.registered";
    public static final String PASSWORD_RESET    = "password.reset";
}
