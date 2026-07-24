package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyMembershipRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsWalletRepository;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private final LoyaltyPointsWalletRepository walletRepository;
    private final TierEngine tierEngine;

    /**
     * Compute a redemption quote WITHOUT mutating state.  Safe to call
     * from pricing endpoints — no lock, no ledger row.
     */
    @Transactional(readOnly = true)
    public RedeemQuote quote(QuoteRequest req) {
        if (req == null) return RedeemQuote.rejected(0, "INVALID_REQUEST");
        return compute(req.membershipId(), req.bingeId(), req.pointsToBurn(),
                req.bookingAmount(), req.at());
    }

    /**
     * Compute the redemption ceiling for a member on a booking — how many points
     * they <b>could</b> apply and the discount that yields — without mutating
     * state. Drives the customer's "use points" slider: its max stop, the
     * minimum, and the live per-point value. The ceiling is the lesser of the
     * wallet balance and the points needed to hit the booking's max-redeemable
     * cap, so the slider can never request more than is actually redeemable.
     */
    @Transactional(readOnly = true)
    public RedeemMax maxRedeemable(Long membershipId, Long bingeId, BigDecimal bookingAmount, LocalDateTime at) {
        if (at == null) at = LocalDateTime.now(ZoneOffset.UTC);
        if (membershipId == null || bingeId == null
                || bookingAmount == null || bookingAmount.signum() <= 0) {
            return RedeemMax.none();
        }
        LoyaltyPointsWallet wallet = walletRepository.findByMembershipId(membershipId).orElse(null);
        long balance = wallet == null ? 0L : wallet.getCurrentBalance();
        if (balance <= 0) return RedeemMax.none();

        // Quote at full balance: an eligible quote returns the capped ceiling
        // (pointsToBurn) and its currency value directly.
        RedeemQuote q = compute(membershipId, bingeId, balance, bookingAmount, at);
        if (q.eligible()) {
            long ppcu = pointsPerCurrencyUnitFor(bingeId, at);
            return new RedeemMax(true, q.pointsToBurn(), q.currencyValue(), ppcu, minPointsFor(bingeId, at), null);
        }
        // Ineligible at full balance (e.g. balance below the minimum): surface
        // the reason and the configured minimum so the UI can explain it.
        return new RedeemMax(false, 0, BigDecimal.ZERO,
                pointsPerCurrencyUnitFor(bingeId, at), minPointsFor(bingeId, at), q.rejectReason());
    }

    private long pointsPerCurrencyUnitFor(Long bingeId, LocalDateTime at) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        return configService.findActiveBinding(program.getId(), bingeId)
                .map(b -> configService.resolveEffectiveRedemption(b.getId(), bingeId, at).pointsPerCurrencyUnit())
                .orElse(0L);
    }

    private long minPointsFor(Long bingeId, LocalDateTime at) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        return configService.findActiveBinding(program.getId(), bingeId)
                .map(b -> configService.resolveEffectiveRedemption(b.getId(), bingeId, at).minRedemptionPoints())
                .orElse(0L);
    }

    /**
     * Actually burn the points.  Idempotent on {@code (bookingRef,
     * pointsToBurn)} — if you call this twice for the same booking with
     * the same points, the second call is a no-op.
     */
    @Transactional
    public RedeemResult burn(BurnRequest req) {
        if (req == null) return RedeemResult.rejected("INVALID_REQUEST");
        LocalDateTime mutationAt = req.at() == null ? LocalDateTime.now(ZoneOffset.UTC) : req.at();
        RedeemQuote q = compute(req.membershipId(), req.bingeId(), req.pointsToBurn(),
                req.bookingAmount(), mutationAt);
        if (!q.eligible()) {
            return RedeemResult.rejected(q.rejectReason());
        }

        try {
            walletService.debit(new PointsWalletService.DebitRequest(
                    req.membershipId(),
                    q.pointsToBurn(),
                    LoyaltyV2Constants.LEDGER_REDEEM,
                    req.bingeId(),
                    req.bookingRef(),
                    "redeem:booking=" + req.bookingRef() + ",pts=" + q.pointsToBurn(),
                    req.correlationId(),
                    "BOOKING_REDEMPTION",
                    "Redemption at binge " + req.bingeId() + " on booking " + req.bookingRef()
                            + " worth " + q.currencyValue(),
                    null,
                    "CUSTOMER"
            ));
        } catch (PointsWalletService.InsufficientPointsException ex) {
            log.info("[loyalty-v2] redemption rejected after wallet lock: {}", ex.getMessage());
            return RedeemResult.rejected("INSUFFICIENT_POINTS");
        }

        // Redemption shouldn't change qualification credits or tier, but
        // we DO want the summary to refresh (lifetime_redeemed counter, etc.).
        // TierEngine.recalc is cheap; calling it is harmless and keeps the
        // membership snapshot consistent after every wallet mutation.
        tierEngine.recalculateTier(req.membershipId(), mutationAt);

        return RedeemResult.accepted(q.pointsToBurn(), q.currencyValue());
    }

    // ── Core computation ─────────────────────────────────────────────────

    private RedeemQuote compute(Long membershipId, Long bingeId, long pointsToBurn,
                                BigDecimal bookingAmount, LocalDateTime at) {
        if (membershipId == null)
            return RedeemQuote.rejected(pointsToBurn, "INVALID_MEMBERSHIP");
        LoyaltyMembership membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new IllegalStateException("Membership " + membershipId + " not found"));

        if (bingeId == null)
            return RedeemQuote.rejected(pointsToBurn, "INVALID_BINGE");
        if (pointsToBurn <= 0)
            return RedeemQuote.rejected(pointsToBurn, "INVALID_POINTS");
        if (bookingAmount == null || bookingAmount.signum() <= 0)
            return RedeemQuote.rejected(pointsToBurn, "INVALID_BOOKING_AMOUNT");
        if (at == null) at = LocalDateTime.now(ZoneOffset.UTC);

        LoyaltyProgram program = configService.requireDefaultProgram();
        Optional<LoyaltyBingeBinding> bindingOpt = configService.findActiveBinding(program.getId(), bingeId);
        if (bindingOpt.isEmpty())
            return RedeemQuote.rejected(pointsToBurn, "NO_BINDING");

        LoyaltyBingeBinding binding = bindingOpt.get();
        if (binding.isLegacyFrozen())
            return RedeemQuote.rejected(pointsToBurn, "LEGACY_FROZEN");

        // Effective economics: per-binge override → venue's country config →
        // program default. This is what makes a point worth the VENUE's country
        // value at redemption, and live-editable from the super-admin Countries
        // tab for any binge that hasn't set its own override.
        LoyaltyConfigService.EffectiveRedemptionTerms terms =
                configService.resolveEffectiveRedemption(binding.getId(), bingeId, at);

        if (terms.pointsPerCurrencyUnit() <= 0)
            return RedeemQuote.rejected(pointsToBurn, "INVALID_REDEEM_RULE");
        if (terms.maxRedemptionPercent() == null || terms.maxRedemptionPercent().signum() <= 0)
            return RedeemQuote.rejected(pointsToBurn, "REDEMPTION_DISABLED");
        if (pointsToBurn < terms.minRedemptionPoints())
            return RedeemQuote.rejected(pointsToBurn, "BELOW_MIN_POINTS");

        BigDecimal tierBonusPct = resolveTierBonus(terms.tierBonusPctJson(), membership.getCurrentTierCode());
        BigDecimal currencyValue = pointsToCurrency(pointsToBurn, terms.pointsPerCurrencyUnit(), tierBonusPct);

        // Cap by maxRedemptionPercent of booking amount.
        BigDecimal maxByPct = bookingAmount
                .multiply(terms.maxRedemptionPercent())
                .divide(new BigDecimal("100"), 2, RoundingMode.FLOOR);
        long effectivePoints = pointsToBurn;
        String note = null;
        if (currencyValue.compareTo(maxByPct) > 0) {
            currencyValue = maxByPct;
            // Also scale down the points so we burn exactly what's
            // redeemable — never overcharge the member.
            effectivePoints = currencyToPoints(maxByPct, terms.pointsPerCurrencyUnit(), tierBonusPct);
            note = "CAPPED_BY_BOOKING_PCT";
        }

        if (effectivePoints <= 0 || currencyValue.signum() <= 0)
            return RedeemQuote.rejected(pointsToBurn, "NO_REDEEMABLE_VALUE");
        if (effectivePoints < terms.minRedemptionPoints())
            return RedeemQuote.rejected(effectivePoints, "BELOW_MIN_POINTS_AFTER_CAP");

        LoyaltyPointsWallet wallet = walletRepository.findByMembershipId(membershipId).orElse(null);
        if (wallet == null)
            return RedeemQuote.rejected(effectivePoints, "NO_WALLET");
        if (wallet.getCurrentBalance() < effectivePoints)
            return RedeemQuote.rejected(effectivePoints, "INSUFFICIENT_POINTS");

        return RedeemQuote.accepted(effectivePoints, currencyValue, tierBonusPct, note);
    }

    private BigDecimal resolveTierBonus(String tierBonusPctJson, String tierCode) {
        if (tierBonusPctJson == null || tierBonusPctJson.isBlank()) return BigDecimal.ZERO;
        try {
            Map<String, Object> map = MAPPER.readValue(tierBonusPctJson, Map.class);
            Object v = map.get(tierCode);
            if (v == null) return BigDecimal.ZERO;
            return new BigDecimal(v.toString());
        } catch (JsonProcessingException e) {
            log.warn("[loyalty-v2] malformed tier_bonus_pct_json: {}", e.getMessage());
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

    /**
     * Ceiling for the customer redemption slider.
     * @param maxPoints            most points applicable to this booking (0 if none)
     * @param maxDiscount          currency discount at {@code maxPoints}
     * @param pointsPerCurrencyUnit points needed for one currency unit off (per venue country)
     * @param minRedemptionPoints  smallest redeemable amount (slider floor)
     * @param reason               why nothing is redeemable, when {@code eligible} is false
     */
    public record RedeemMax(
            boolean eligible,
            long maxPoints,
            BigDecimal maxDiscount,
            long pointsPerCurrencyUnit,
            long minRedemptionPoints,
            String reason
    ) {
        public static RedeemMax none() {
            return new RedeemMax(false, 0, BigDecimal.ZERO, 0, 0, "NO_POINTS");
        }
    }
}
