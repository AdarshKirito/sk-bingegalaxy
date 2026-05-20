package com.skbingegalaxy.booking.service.statemachine;

/**
 * Domain events that drive {@link BookingStateMachine} transitions.
 *
 * <p>The state machine is <em>event-driven</em>, not state-to-state: each
 * transition is identified by the pair {@code (currentStatus, event)}. This
 * means the same target status can be reached by different events with
 * different actor permissions and audit semantics — for example
 * {@link #CUSTOMER_CANCEL} vs {@link #ADMIN_CANCEL} both end at
 * {@code CANCELLED} but are recorded distinctly in the audit log.
 */
public enum BookingTransitionEvent {

    /** Payment service confirmed full payment captured (Kafka payment.success → SYSTEM). */
    PAYMENT_SUCCEEDED,

    /** Admin manually flips a PENDING booking to CONFIRMED via PATCH /admin/{ref}. */
    ADMIN_CONFIRM,

    /** Customer self-cancels their own PENDING/CONFIRMED booking. */
    CUSTOMER_CANCEL,

    /** Admin or super-admin cancels a PENDING/CONFIRMED booking from the admin console. */
    ADMIN_CANCEL,

    /**
     * System-triggered cancellation (payment failed, pending-timeout sweep,
     * compensation saga). Always SYSTEM actor.
     */
    SYSTEM_AUTO_CANCEL,

    /** Admin checks the customer in at the venue. */
    CHECK_IN,

    /** Admin records session end (early checkout or scheduled end). */
    CHECK_OUT,

    /** Admin reverts a check-in back to CONFIRMED (reason required). */
    UNDO_CHECK_IN,

    /** Daily-audit / no-show automation flips PENDING/CONFIRMED → NO_SHOW. */
    MARK_NO_SHOW,

    /**
     * SUPER_ADMIN explicit override that bypasses the normal transition table.
     * Reason is mandatory and the audit event is logged with elevated
     * severity. Use only via {@link BookingStateMachine#override}.
     */
    ADMIN_OVERRIDE
}
