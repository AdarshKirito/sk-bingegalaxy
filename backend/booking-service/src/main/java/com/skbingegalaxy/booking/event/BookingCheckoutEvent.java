package com.skbingegalaxy.booking.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * In-process Spring event fired when a customer reaches the checkout
 * screen — BEFORE payment.  Used by the loyalty redemption preview
 * and tier-discount preview so the customer sees their potential
 * savings in real time.
 *
 * <p>Listeners MUST be idempotent since a single checkout flow may
 * emit this event multiple times (e.g. customer toggles add-ons).
 * This event does NOT mutate wallet / ledger state — it only drives
 * read-side computations surfaced in the checkout UI.
 */
public record BookingCheckoutEvent(
        Long customerId,
        Long bingeId,
        Long tenantId,
        BigDecimal totalAmount,
        LocalDateTime at
) { }
