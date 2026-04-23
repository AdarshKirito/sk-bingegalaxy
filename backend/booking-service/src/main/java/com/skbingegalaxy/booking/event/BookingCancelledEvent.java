package com.skbingegalaxy.booking.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * In-process Spring event fired when a booking is cancelled.
 *
 * <p>Drives loyalty reversal: if points were earned for this booking,
 * the {@code LoyaltyV2BookingListener} writes a REVERSE_EARN ledger
 * entry and decrements the wallet.  If points were redeemed against
 * this booking, a REVERSE_REDEEM entry refunds them.
 *
 * <p>{@code refundAmount} may be less than the original total if
 * cancellation fees apply.  The loyalty reversal is proportional to
 * {@code refundAmount / totalAmount}.
 */
public record BookingCancelledEvent(
        Long bookingId,
        String bookingRef,
        Long customerId,
        Long bingeId,
        Long tenantId,
        BigDecimal totalAmount,
        BigDecimal refundAmount,
        String cancelReason,
        LocalDateTime cancelledAt
) { }
