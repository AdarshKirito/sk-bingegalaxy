package com.skbingegalaxy.booking.entity;

/**
 * Lifecycle state of a {@link Binge} within the super-admin approval workflow.
 *
 * <ul>
 *   <li>{@link #PENDING_APPROVAL} — created by a regular ADMIN; not yet visible
 *       to customers and cannot accept bookings until approved.</li>
 *   <li>{@link #APPROVED} — visible to customers (subject to {@code active} flag
 *       and the binge having at least one active event type).</li>
 *   <li>{@link #REJECTED} — super-admin declined the request; row is retained
 *       for audit but never surfaces to customers.</li>
 * </ul>
 */
public enum BingeApprovalStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}
