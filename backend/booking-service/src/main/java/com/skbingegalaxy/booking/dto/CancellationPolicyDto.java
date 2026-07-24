package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Read + write payload for binge-level cancellation policy
 * (refund applicability flags + freeze policy thresholds).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationPolicyDto {

    /** Master switch for the freeze machinery. */
    @NotNull
    private Boolean freezePolicyEnabled;

    @NotNull
    @Min(value = 1, message = "freezeDurationMinutes must be at least 1")
    private Integer freezeDurationMinutes;

    @NotNull
    @Min(value = 1, message = "maxPendingCancelsBeforeFreeze must be at least 1")
    private Integer maxPendingCancelsBeforeFreeze;

    @NotNull
    @Min(value = 1, message = "maxPendingPaymentTimeoutsBeforeFreeze must be at least 1")
    private Integer maxPendingPaymentTimeoutsBeforeFreeze;

    /**
     * Concurrent unpaid (PENDING) bookings a customer may hold at this venue before
     * new bookings are stopped. Admin decides this per binge (was a platform hardcode).
     *
     * <p>Intentionally NOT {@code @NotNull}: clients running the previous bundle (stale
     * PWA service worker) don't send this field — null means "keep the current value"
     * so their policy saves keep working instead of failing validation.
     */
    @Min(value = 1, message = "maxUnpaidBookingsPerCustomer must be at least 1")
    private Integer maxUnpaidBookingsPerCustomer;

    /** Whether tiered refunds apply when cancelling after a successful payment. */
    @NotNull
    private Boolean refundOnSuccessfulPaymentCancel;

    /** Whether tiered refunds apply when cancelling while still pending payment. */
    @NotNull
    private Boolean refundOnPendingPaymentCancel;
}
