package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.engine.PointsWalletService;
import com.skbingegalaxy.booking.loyalty.v2.engine.RedeemEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Loyalty v2 — customer account service.
 *
 * <p>Central read and write surface for customer-facing loyalty operations:
 * profile retrieval, points redemption at checkout, and admin point
 * adjustments.  All mutations route through {@link PointsWalletService}
 * and {@link RedeemEngine} to guarantee full ledger integrity and
 * idempotency.
 *
 * <p>This class supersedes the retired {@code booking.service.LoyaltyService}
 * v1 facade.  All callers that previously used the v1 facade now use this
 * class directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyMemberService {

    private final EnrollmentService enrollmentService;
    private final LoyaltyConfigService configService;
    private final PointsWalletService walletService;
    private final RedeemEngine redeemEngine;
    private final LoyaltyPointsWalletRepository walletRepository;

    private static final List<String> STUB_TIER_ORDER =
            List.of(LoyaltyV2Constants.TIER_BRONZE, LoyaltyV2Constants.TIER_SILVER,
                    LoyaltyV2Constants.TIER_GOLD,   LoyaltyV2Constants.TIER_PLATINUM);
    private static final long STUB_NEXT_TIER_THRESHOLD = 5_000L;
    private static final long MAX_SINGLE_ADJUSTMENT = 100_000L;

    // ── Profile ───────────────────────────────────────────────────────────

    /**
     * Return a complete membership snapshot for {@code customerId}.
     * Read-only; never enrolls the customer.  Returns an un-enrolled
     * stub profile if the customer has no membership yet.
     */
    @Transactional(readOnly = true)
    public MemberProfile getMemberProfile(Long customerId) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        int redemptionRate = configService.resolveDisplayRedemptionRate(
                program.getId(), LocalDateTime.now());

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElse(null);
        if (m == null) {
            return emptyProfile(customerId, redemptionRate);
        }

        LoyaltyPointsWallet w = walletRepository.findByMembershipId(m.getId()).orElse(null);
        long balance  = w == null ? 0L : w.getCurrentBalance();
        long earned   = w == null ? 0L : w.getLifetimeEarned();
        long redeemed = w == null ? 0L : w.getLifetimeRedeemed();

        TierGap gap = computeTierGap(
                m.getProgramId(), m.getCurrentTierCode(), m.getQualifyingCreditsWindow());

        return new MemberProfile(
                m.getId(),
                customerId,
                m.getMemberNumber(),
                balance,
                earned,
                redeemed,
                m.getCurrentTierCode(),
                gap.pointsToNext(),
                gap.nextTierCode(),
                m.getTierEffectiveFrom(),
                m.getTierEffectiveUntil(),
                m.getQualifyingCreditsWindow(),
                m.getLifetimeCredits(),
                redemptionRate,
                m.getEnrolledAt(),
                m.getUpdatedAt()
        );
    }

    private MemberProfile emptyProfile(Long customerId, int redemptionRate) {
        return new MemberProfile(
                null, customerId, null,
                0L, 0L, 0L,
                STUB_TIER_ORDER.get(0),
                STUB_NEXT_TIER_THRESHOLD,
                STUB_TIER_ORDER.get(1),
                null, null,
                0L, 0L,
                redemptionRate,
                null, null
        );
    }

    // ── Redemption at checkout ────────────────────────────────────────────

    /**
     * Burn loyalty points during booking checkout and return the resulting
     * currency discount.  Best-effort: any failure returns a zero result
     * rather than failing the booking.  Idempotent on
     * {@code (bookingRef, requestedPoints)}.
     */
    @Transactional
    public RedemptionResult redeemForBooking(Long customerId, Long bingeId,
                                             String bookingRef, long requestedPoints,
                                             BigDecimal maxDiscount) {
        configService.requireDefaultProgram();
        if (bingeId == null) {
            log.warn("[loyalty-v2] redeem skipped — bingeId not supplied (booking={})", bookingRef);
            return RedemptionResult.ZERO;
        }

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElse(null);
        if (m == null) {
            log.debug("[loyalty-v2] redeem skipped — customer {} not enrolled (booking={})",
                    customerId, bookingRef);
            return RedemptionResult.ZERO;
        }

        LoyaltyPointsWallet w = walletRepository.findByMembershipId(m.getId()).orElse(null);
        if (w == null || w.getCurrentBalance() <= 0) {
            return RedemptionResult.ZERO;
        }

        long pointsToBurn = Math.min(requestedPoints, w.getCurrentBalance());
        if (pointsToBurn <= 0) return RedemptionResult.ZERO;

        try {
            RedeemEngine.RedeemResult res = redeemEngine.burn(new RedeemEngine.BurnRequest(
                    m.getId(), bingeId, bookingRef, pointsToBurn,
                    maxDiscount, LocalDateTime.now(), "redeem:" + bookingRef));

            if (!res.accepted()) {
                log.info("[loyalty-v2] redeem rejected for booking {} — reason={}",
                        bookingRef, res.rejectReason());
                return RedemptionResult.ZERO;
            }
            BigDecimal applied = res.currencyApplied().min(maxDiscount);
            log.info("[loyalty-v2] redeemed {} pts (₹{} discount) customer={} booking={}",
                    res.pointsBurned(), applied, customerId, bookingRef);
            return new RedemptionResult(res.pointsBurned(), applied);
        } catch (RuntimeException ex) {
            log.warn("[loyalty-v2] redeem failed for booking {} — {}", bookingRef, ex.getMessage());
            return RedemptionResult.ZERO;
        }
    }

    // ── Admin: manual point adjustment ───────────────────────────────────

    /**
     * Credit (positive {@code points}) or debit (negative {@code points})
     * the customer's wallet as an admin action.  Non-super-admins are
     * capped at {@value #MAX_SINGLE_ADJUSTMENT} per call.  Returns the
     * refreshed member profile.
     */
    @Transactional
    public MemberProfile adjustPoints(Long customerId, long points,
                                      String description, String role) {
        boolean isSuperAdmin = role != null && role.toUpperCase().contains("SUPER_ADMIN");
        if (!isSuperAdmin && Math.abs(points) > MAX_SINGLE_ADJUSTMENT) {
            throw new BusinessException(
                    "Single adjustment cannot exceed " + MAX_SINGLE_ADJUSTMENT
                    + " points.  Only SUPER_ADMIN can perform larger adjustments.");
        }

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId)
                .orElseGet(() -> enrollmentService.enrollExplicit(
                        customerId, LoyaltyV2Constants.ENROLL_ADMIN_IMPORT));

        LoyaltyPointsWallet w = walletRepository.findByMembershipId(m.getId()).orElse(null);
        long currentBalance = w == null ? 0L : w.getCurrentBalance();
        if (points < 0 && currentBalance + points < 0) {
            throw new BusinessException("Insufficient loyalty balance for this adjustment");
        }

        LocalDateTime now = LocalDateTime.now();
        String desc = description != null ? description : "Admin adjustment";
        String idempotencyKey = "adjust:" + customerId + ":"
                + now.toEpochSecond(ZoneOffset.UTC) + ":" + points;
        String actorRole = isSuperAdmin ? "SUPER_ADMIN" : "ADMIN";

        if (points > 0) {
            walletService.credit(new PointsWalletService.CreditRequest(
                    m.getId(), points,
                    LoyaltyV2Constants.LEDGER_ADJUST, "ADMIN_ADJUSTMENT",
                    "adjust:customer=" + customerId,
                    null, null,
                    now, now.plusYears(10),
                    idempotencyKey, null,
                    "ADMIN_ADJUSTMENT", desc,
                    null, actorRole
            ));
        } else if (points < 0) {
            walletService.debit(new PointsWalletService.DebitRequest(
                    m.getId(), -points,
                    LoyaltyV2Constants.LEDGER_ADJUST,
                    null, null,
                    idempotencyKey, null,
                    "ADMIN_ADJUSTMENT", desc,
                    null, actorRole
            ));
        }
        return getMemberProfile(customerId);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private TierGap computeTierGap(Long programId, String currentTierCode,
                                   long currentQualifyingCredits) {
        try {
            List<LoyaltyTierDefinition> ladder =
                    configService.activeLadder(programId, LocalDateTime.now());
            int idx = -1;
            for (int i = 0; i < ladder.size(); i++) {
                if (ladder.get(i).getCode().equals(currentTierCode)) { idx = i; break; }
            }
            if (idx < 0) return new TierGap(null, null);

            for (int i = idx + 1; i < ladder.size(); i++) {
                LoyaltyTierDefinition next = ladder.get(i);
                if (!isProgressiveTier(next)) continue;
                long gap = Math.max(0L,
                        next.getQualificationCreditsRequired() - currentQualifyingCredits);
                return new TierGap(gap, next.getCode());
            }
        } catch (RuntimeException ex) {
            log.debug("[loyalty-v2] tier-gap fallback for tier {}: {}",
                    currentTierCode, ex.getMessage());
        }
        return new TierGap(null, null);
    }

    private static boolean isProgressiveTier(LoyaltyTierDefinition t) {
        if (t == null || t.getCode() == null) return false;
        if (LoyaltyV2Constants.TIER_CUSTOMER_PWN.equals(t.getCode())) return false;
        if (t.getLifetimeCreditsRequired() != null) return false;
        if (t.getLifetimeYearsHeldRequired() != null) return false;
        return t.getQualificationCreditsRequired() > 0L;
    }

    private record TierGap(Long pointsToNext, String nextTierCode) {}

    // ── Public response types ─────────────────────────────────────────────

    /**
     * Loyalty membership snapshot returned to API callers.
     *
     * <p>Canonical v2 shape — field names match the JSON keys sent to the
     * frontend.  No legacy aliases; the frontend maps these directly.
     */
    public record MemberProfile(
            Long   membershipId,
            Long   customerId,
            String memberNumber,
            long   pointsBalance,
            long   pointsEarnedLifetime,
            long   pointsRedeemedLifetime,
            String tierCode,
            Long   pointsToNextTier,
            String nextTierCode,
            LocalDateTime tierEffectiveFrom,
            LocalDateTime tierEffectiveUntil,
            long   qualifyingCreditsWindow,
            long   lifetimeCredits,
            int    redemptionRate,
            LocalDateTime enrolledAt,
            LocalDateTime updatedAt
    ) {}

    /** Checkout redemption outcome returned to {@code BookingService}. */
    public record RedemptionResult(long pointsRedeemed, BigDecimal discountAmount) {
        static final RedemptionResult ZERO = new RedemptionResult(0, BigDecimal.ZERO);
    }
}
