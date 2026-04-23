package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyMembershipRepository;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Loyalty v2 — REDEEM engine.
 *
 * <p>Converts a member's request to apply points to a booking into
 * <ul>
 *   <li>a ledger debit (via {@link PointsWalletService#debit}), and</li>
 *   <li>a currency discount value returned to the caller.</li>
 * </ul>
 *
 * <p>Conversion rate comes from the binge's active
 * {@link LoyaltyBingeRedemptionRule}, optionally boosted by a
 * tier-specific bonus from {@code tier_bonus_pct_json}.  A 5% bonus for
 * Gold means: if the base rule is 100 pts / ₹1, a Gold member effectively
 * burns at 95.24 pts / ₹1 (₹ value of their burn is 5% higher).
 *
 * <p>Guardrails enforced:
 * <ul>
 *   <li>Minimum redemption threshold ({@code minRedemptionPoints}).</li>
 *   <li>Maximum fraction of the booking that can be paid with points
 *       ({@code maxRedemptionPercent}).</li>
 *   <li>Wallet balance ≥ requested points (hard, via DB CHECK).</li>
 * </ul>
 *
 * <p>Returns a quote-or-commit style result so callers can either just
 * preview a redemption ({@code quote}) or actually burn points
 * ({@code burn}).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedeemEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LoyaltyConfigService configService;
    private final PointsWalletService walletService;
    private final LoyaltyMembershipRepository membershipRepository;
    private final TierEngine tierEngine;

    /**
     * Compute a redemption quote WITHOUT mutating state.  Safe to call
     * from pricing endpoints — no lock, no ledger row.
     */
    @Transactional(readOnly = true)
    public RedeemQuote quote(QuoteRequest req) {
        return compute(req.membershipId(), req.bingeId(), req.pointsToBurn(),
                req.bookingAmount(), req.at());
    }

    /**
     * Actually burn the points.  Idempotent on {@code (bookingRef,
     * pointsToBurn)} — if you call this twice for the same booking with
     * the same points, the second call is a no-op.
     */
    @Transactional
    public RedeemResult burn(BurnRequest req) {
        RedeemQuote q = compute(req.membershipId(), req.bingeId(), req.pointsToBurn(),
                req.bookingAmount(), req.at());
        if (!q.eligible()) {
            return RedeemResult.rejected(q.rejectReason());
        }

        walletService.debit(new PointsWalletService.DebitRequest(
                req.membershipId(),
                req.pointsToBurn(),
                LoyaltyV2Constants.LEDGER_REDEEM,
                req.bingeId(),
                req.bookingRef(),
                "redeem:booking=" + req.bookingRef() + ",pts=" + req.pointsToBurn(),
                req.correlationId(),
                "BOOKING_REDEMPTION",
                "Redemption at binge " + req.bingeId() + " on booking " + req.bookingRef()
                        + " worth " + q.currencyValue(),
                null,
                "CUSTOMER"
        ));

        // Redemption shouldn't change qualification credits or tier, but
        // we DO want the summary to refresh (lifetime_redeemed counter, etc.).
        // TierEngine.recalc is cheap; calling it is harmless and keeps the
        // membership snapshot consistent after every wallet mutation.
        tierEngine.recalculateTier(req.membershipId(), req.at());

        return RedeemResult.accepted(q.pointsToBurn(), q.currencyValue());
    }

    // ── Core computation ─────────────────────────────────────────────────

    private RedeemQuote compute(Long membershipId, Long bingeId, long pointsToBurn,
                                BigDecimal bookingAmount, LocalDateTime at) {
        LoyaltyMembership membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new IllegalStateException("Membership " + membershipId + " not found"));

        LoyaltyProgram program = configService.requireDefaultProgram();
        Optional<LoyaltyBingeBinding> bindingOpt = configService.findActiveBinding(program.getId(), bingeId);
        if (bindingOpt.isEmpty())
            return RedeemQuote.rejected(pointsToBurn, "NO_BINDING");

        LoyaltyBingeBinding binding = bindingOpt.get();
        if (binding.isLegacyFrozen())
            return RedeemQuote.rejected(pointsToBurn, "LEGACY_FROZEN");

        Optional<LoyaltyBingeRedemptionRule> ruleOpt = configService.resolveRedemptionRule(binding.getId(), at);
        if (ruleOpt.isEmpty())
            return RedeemQuote.rejected(pointsToBurn, "NO_REDEEM_RULE");

        LoyaltyBingeRedemptionRule rule = ruleOpt.get();

        if (pointsToBurn < rule.getMinRedemptionPoints())
            return RedeemQuote.rejected(pointsToBurn, "BELOW_MIN_POINTS");

        BigDecimal tierBonusPct = resolveTierBonus(rule, membership.getCurrentTierCode());
        BigDecimal currencyValue = pointsToCurrency(pointsToBurn, rule.getPointsPerCurrencyUnit(), tierBonusPct);

        // Cap by maxRedemptionPercent of booking amount.
        BigDecimal maxByPct = bookingAmount
                .multiply(rule.getMaxRedemptionPercent())
                .divide(new BigDecimal("100"), 2, RoundingMode.FLOOR);
        if (currencyValue.compareTo(maxByPct) > 0) {
            currencyValue = maxByPct;
            // Also scale down the points so we burn exactly what's
            // redeemable — never overcharge the member.
            long cappedPts = currencyToPoints(maxByPct, rule.getPointsPerCurrencyUnit(), tierBonusPct);
            return RedeemQuote.accepted(cappedPts, currencyValue, tierBonusPct, "CAPPED_BY_BOOKING_PCT");
        }

        return RedeemQuote.accepted(pointsToBurn, currencyValue, tierBonusPct, null);
    }

    private BigDecimal resolveTierBonus(LoyaltyBingeRedemptionRule rule, String tierCode) {
        if (rule.getTierBonusPctJson() == null || rule.getTierBonusPctJson().isBlank()) return BigDecimal.ZERO;
        try {
            Map<String, Object> map = MAPPER.readValue(rule.getTierBonusPctJson(), Map.class);
            Object v = map.get(tierCode);
            if (v == null) return BigDecimal.ZERO;
            return new BigDecimal(v.toString());
        } catch (JsonProcessingException e) {
            log.warn("[loyalty-v2] malformed tier_bonus_pct_json on rule {}: {}", rule.getId(), e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal pointsToCurrency(long points, long pointsPerCurrencyUnit, BigDecimal tierBonusPct) {
        if (pointsPerCurrencyUnit <= 0) return BigDecimal.ZERO;
        BigDecimal base = BigDecimal.valueOf(points)
                .divide(BigDecimal.valueOf(pointsPerCurrencyUnit), 6, RoundingMode.FLOOR);
        BigDecimal bonus = base.multiply(tierBonusPct).divide(new BigDecimal("100"), 6, RoundingMode.FLOOR);
        return base.add(bonus).setScale(2, RoundingMode.FLOOR);
    }

    private long currencyToPoints(BigDecimal currency, long pointsPerCurrencyUnit, BigDecimal tierBonusPct) {
        if (pointsPerCurrencyUnit <= 0) return 0;
        // invert: currency = pts/ppcu * (1 + bonus/100)  =>  pts = currency * ppcu / (1 + bonus/100)
        BigDecimal divisor = BigDecimal.ONE.add(tierBonusPct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        BigDecimal pts = currency.multiply(BigDecimal.valueOf(pointsPerCurrencyUnit))
                .divide(divisor, 6, RoundingMode.CEILING);
        return pts.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public record QuoteRequest(
            Long membershipId,
            Long bingeId,
            long pointsToBurn,
            BigDecimal bookingAmount,
            LocalDateTime at
    ) { }

    public record BurnRequest(
            Long membershipId,
            Long bingeId,
            String bookingRef,
            long pointsToBurn,
            BigDecimal bookingAmount,
            LocalDateTime at,
            String correlationId
    ) { }

    public record RedeemQuote(
            boolean eligible,
            long pointsToBurn,
            BigDecimal currencyValue,
            BigDecimal tierBonusPct,
            String note,
            String rejectReason
    ) {
        public static RedeemQuote accepted(long pts, BigDecimal cur, BigDecimal bonus, String note) {
            return new RedeemQuote(true, pts, cur, bonus, note, null);
        }
        public static RedeemQuote rejected(long pts, String reason) {
            return new RedeemQuote(false, pts, BigDecimal.ZERO, BigDecimal.ZERO, null, reason);
        }
    }

    public record RedeemResult(
            boolean accepted,
            long pointsBurned,
            BigDecimal currencyApplied,
            String rejectReason
    ) {
        public static RedeemResult accepted(long pts, BigDecimal cur) {
            return new RedeemResult(true, pts, cur, null);
        }
        public static RedeemResult rejected(String reason) {
            return new RedeemResult(false, 0, BigDecimal.ZERO, reason);
        }
    }
}
