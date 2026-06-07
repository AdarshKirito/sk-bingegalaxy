package com.skbingegalaxy.common.enums;

public enum PaymentStatus {
    PENDING,
    INITIATED,
    SUCCESS,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    PARTIALLY_PAID,
    /**
     * A chargeback or bank dispute has been raised by the customer via their
     * payment provider (Razorpay dispute.created webhook). Money is held by
     * the gateway pending resolution. The booking stays confirmed — do not
     * cancel automatically; wait for dispute.won / dispute.lost events.
     */
    DISPUTED
}
