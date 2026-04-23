package com.skbingegalaxy.booking.loyalty.v2.perk;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingeBinding;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyMembership;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService.PerkResolution;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Loyalty v2 — extension point for perk delivery.
 *
 * <p>Each platform perk in {@code loyalty_perk_catalog} references a
 * {@code deliveryHandlerKey} string.  At runtime, {@link PerkRegistry}
 * resolves that key to a Spring bean of this type — one handler per
 * perk type.  This is the open/closed boundary: adding a new perk
 * means inserting a catalog row and dropping in a new
 * {@code @Component} implementing this interface and declaring its
 * handler key.
 *
 * <p>Handlers fall into three broad flavors:
 * <ul>
 *   <li>{@code FINANCIAL}: affects pricing or wallet ({@code TIER_DISCOUNT_PCT},
 *       {@code BONUS_MULTIPLIER}, {@code FREE_CANCELLATION_24H}).
 *       Consulted during {@code BookingService} pricing &amp; cancellation flows.</li>
 *   <li>{@code SOFT}: affects scheduling/visibility ({@code PRIORITY_WAITLIST},
 *       {@code EARLY_ACCESS_BOOKING}).  Consulted by availability /
 *       waitlist services.</li>
 *   <li>{@code INVISIBLE}: runs autonomously ({@code SURPRISE_DELIGHT},
 *       {@code STATUS_EXTENSION}).  Triggered by scheduled jobs or
 *       admin actions.</li>
 * </ul>
 *
 * <p>All handlers are called with an {@link EvaluationContext} giving
 * them everything they need (member, binge binding, perk configuration,
 * booking amount, timestamp) without coupling the caller to perk
 * internals.
 */
public interface PerkDeliveryHandler {

    /** The {@code delivery_handler_key} from the catalog row this handler services. */
    String handlerKey();

    /**
     * Evaluate and (optionally) deliver the perk.
     *
     * <p>For {@code FINANCIAL} perks this typically returns a non-null
     * {@code discountAmount} or a bonus-points delta the caller should
     * apply.  For {@code SOFT} / {@code INVISIBLE} perks it typically
     * emits a side-effect (priority score update, event, etc.) and
     * returns a terse summary.
     */
    PerkOutcome evaluate(EvaluationContext ctx);

    // ── Shared types ─────────────────────────────────────────────────────

    record EvaluationContext(
            LoyaltyMembership membership,
            LoyaltyBingeBinding binding,
            PerkResolution perk,
            BigDecimal bookingAmount,
            String bookingRef,
            LocalDateTime at
    ) { }

    /**
     * @param discountAmount     optional reduction in booking currency value.
     * @param bonusPointsDelta   optional bonus points to credit (signed).
     * @param priorityBoost      optional integer boost for soft perks.
     * @param note               short human-readable description.
     */
    record PerkOutcome(
            BigDecimal discountAmount,
            long bonusPointsDelta,
            int priorityBoost,
            String note
    ) {
        public static PerkOutcome none() {
            return new PerkOutcome(BigDecimal.ZERO, 0, 0, "no-op");
        }
        public static PerkOutcome discount(BigDecimal amount, String note) {
            return new PerkOutcome(amount, 0, 0, note);
        }
        public static PerkOutcome bonus(long points, String note) {
            return new PerkOutcome(BigDecimal.ZERO, points, 0, note);
        }
        public static PerkOutcome priority(int boost, String note) {
            return new PerkOutcome(BigDecimal.ZERO, 0, boost, note);
        }
    }
}
