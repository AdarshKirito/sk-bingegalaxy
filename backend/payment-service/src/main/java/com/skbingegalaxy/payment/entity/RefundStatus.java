package com.skbingegalaxy.payment.entity;

/**
 * Lifecycle of a single refund row, decoupled from the parent {@code Payment.status}.
 *
 * <p>The parent {@link Payment} column ({@code PaymentStatus}) tracks the overall
 * money-state of the booking (SUCCESS / PARTIALLY_REFUNDED / REFUNDED). A
 * single refund attempt, however, has its own lifecycle that ops needs visibility
 * into — especially when a gateway-side failure leaves the parent payment as
 * SUCCESS while the refund attempt itself is FAILED.
 *
 * <p>Stripe-style states:
 * <ul>
 *   <li>{@link #CALCULATED} — amount computed but not yet sent to gateway.
 *       Reserved for future use; today refunds skip straight to INITIATED.</li>
 *   <li>{@link #INITIATED} — accepted internally; about to call the gateway
 *       (or has been queued in the outbox).</li>
 *   <li>{@link #PROCESSING} — gateway acknowledged but not yet confirmed via
 *       webhook. Reserved for the async gateway path.</li>
 *   <li>{@link #SUCCEEDED} — gateway confirmed (or synchronous gateway returned 2xx).</li>
 *   <li>{@link #FAILED} — gateway returned an error. Surfaced in the admin
 *       failed-refund queue for manual triage / retry.</li>
 *   <li>{@link #SUPERSEDED} — a retry was issued; this row is no longer the
 *       authoritative refund attempt for the same amount/payment pair.</li>
 * </ul>
 *
 * <p>Why a separate enum (not {@code PaymentStatus})? Adding refund-only states
 * to the booking-level {@code PaymentStatus} would force every status switch
 * across booking + payment services to handle states it has no business
 * knowing about, breaking the multi-tenant isolation of concerns.
 */
public enum RefundStatus {
    CALCULATED,
    INITIATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    SUPERSEDED
}
