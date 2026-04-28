package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.config.LoyaltyProperties;
import com.skbingegalaxy.booking.dto.LoyaltyAccountDto;
import com.skbingegalaxy.booking.dto.LoyaltyTransactionDto;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.engine.PointsWalletService;
import com.skbingegalaxy.booking.loyalty.v2.engine.RedeemEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyLedgerEntry;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyMembership;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsWallet;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyTierDefinition;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyLedgerEntryRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsWalletRepository;
import com.skbingegalaxy.booking.loyalty.v2.service.EnrollmentService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Loyalty service — thin v1-shaped facade over the v2 engines.
 *
 * <p>The legacy v1 ledger ({@code loyalty_accounts} / {@code loyalty_transactions})
 * was retired in M13.  Every public method here now reads / writes the
 * v2 wallet via {@link PointsWalletService}, {@link RedeemEngine}, and
 * {@link EnrollmentService}.  The DTO emitted by {@link #getAccount(Long)}
 * keeps its v1 shape so the existing customer-facing endpoint
 * {@code /api/v1/bookings/loyalty} continues to render against v2 data
 * without a breaking change for the frontend.
 *
 * <p>Earn / expire / cancellation reversal are handled directly by the
 * v2 listener {@code LoyaltyV2BookingListener} on transactional events,
 * so they no longer have a method on this facade.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyService {

    private final LoyaltyProperties loyaltyProps;
    private final EnrollmentService enrollmentService;
    private final LoyaltyConfigService loyaltyConfigService;
    private final PointsWalletService walletService;
    private final RedeemEngine redeemEngine;
    private final LoyaltyPointsWalletRepository walletRepository;
    private final LoyaltyLedgerEntryRepository ledgerRepository;

    // ── Empty-stub tier ladder used when a customer has no v2 membership yet.
    //    Matches the v2 default ladder so the UI shows "BRONZE → SILVER" with
    //    a sensible target instead of an empty card.
    private static final List<String> STUB_TIER_ORDER = List.of("BRONZE", "SILVER", "GOLD", "PLATINUM");
    private static final long STUB_NEXT_TIER_THRESHOLD = 5_000L;

    private static final long MAX_SINGLE_ADJUSTMENT = 100_000L;

    // ═══════════════════════════════════════════════════════════
    //  GET ACCOUNT  — v1-shaped read backed by the v2 wallet
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public LoyaltyAccountDto getAccount(Long customerId) {
        if (!loyaltyProps.isEnabled()) {
            return null;
        }

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElse(null);
        if (m == null) {
            return emptyStub(customerId);
        }

        LoyaltyPointsWallet w = walletRepository.findByMembershipId(m.getId()).orElse(null);
        long balance = w == null ? 0L : w.getCurrentBalance();
        long earned  = w == null ? 0L : w.getLifetimeEarned();

        TierGap gap = computeTierGap(m.getProgramId(), m.getCurrentTierCode(), m.getQualifyingCreditsWindow());

        List<LoyaltyTransactionDto> recent = w == null ? List.of()
                : ledgerRepository.findByWalletIdOrderByCreatedAtDesc(w.getId(), PageRequest.of(0, 10))
                    .map(LoyaltyService::ledgerToV1Dto)
                    .getContent();

        return LoyaltyAccountDto.builder()
            .id(m.getId())
            .customerId(customerId)
            .totalPointsEarned(earned)
            .currentBalance(balance)
            .tierLevel(m.getCurrentTierCode())
            .pointsToNextTier(gap.pointsToNext())
            .nextTierLevel(gap.nextTierCode())
            .createdAt(m.getEnrolledAt())
            .updatedAt(m.getUpdatedAt())
            .redemptionRate(loyaltyProps.getRedemptionRate())
            .recentTransactions(recent)
            .build();
    }

    private LoyaltyAccountDto emptyStub(Long customerId) {
        return LoyaltyAccountDto.builder()
            .customerId(customerId)
            .totalPointsEarned(0)
            .currentBalance(0)
            .tierLevel(STUB_TIER_ORDER.get(0))
            .pointsToNextTier(STUB_NEXT_TIER_THRESHOLD)
            .nextTierLevel(STUB_TIER_ORDER.get(1))
            .redemptionRate(loyaltyProps.getRedemptionRate())
            .recentTransactions(List.of())
            .build();
    }

    // ═══════════════════════════════════════════════════════════
    //  REDEEM POINTS  — booking checkout discount
    // ═══════════════════════════════════════════════════════════

    /** Backwards-compatible overload — bingeId-less callers get a no-op. */
    @Transactional
    public RedemptionResult redeemPoints(Long customerId, String bookingRef,
                                          long requestedPoints, BigDecimal maxDiscount) {
        return redeemPoints(customerId, null, bookingRef, requestedPoints, maxDiscount);
    }

    /**
     * Redeem loyalty points for a discount on the given booking, capped
     * at {@code maxDiscount}.  v2 {@link RedeemEngine} additionally
     * applies the binge's per-booking redemption-percent rule.
     */
    @Transactional
    public RedemptionResult redeemPoints(Long customerId, Long bingeId, String bookingRef,
                                          long requestedPoints, BigDecimal maxDiscount) {
        if (!loyaltyProps.isEnabled() || loyaltyProps.getRedemptionRate() <= 0) {
            return new RedemptionResult(0, BigDecimal.ZERO);
        }
        if (bingeId == null) {
            log.warn("[loyalty] redeem skipped — bingeId not supplied (booking={})", bookingRef);
            return new RedemptionResult(0, BigDecimal.ZERO);
        }

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElse(null);
        if (m == null) {
            log.debug("[loyalty] redeem skipped — customer {} not enrolled (booking={})",
                customerId, bookingRef);
            return new RedemptionResult(0, BigDecimal.ZERO);
        }
        LoyaltyPointsWallet w = walletRepository.findByMembershipId(m.getId()).orElse(null);
        if (w == null || w.getCurrentBalance() <= 0) {
            return new RedemptionResult(0, BigDecimal.ZERO);
        }
        long pointsToBurn = Math.min(requestedPoints, w.getCurrentBalance());
        if (pointsToBurn <= 0) {
            return new RedemptionResult(0, BigDecimal.ZERO);
        }

        try {
            RedeemEngine.RedeemResult res = redeemEngine.burn(new RedeemEngine.BurnRequest(
                m.getId(),
                bingeId,
                bookingRef,
                pointsToBurn,
                maxDiscount,
                LocalDateTime.now(),
                "redeem:" + bookingRef
            ));
            if (!res.accepted()) {
                log.info("[loyalty] redeem rejected for booking {} — reason={}",
                    bookingRef, res.rejectReason());
                return new RedemptionResult(0, BigDecimal.ZERO);
            }
            BigDecimal applied = res.currencyApplied();
            long       burned  = res.pointsBurned();
            if (applied.compareTo(maxDiscount) > 0) {
                applied = maxDiscount;
            }
            log.info("[loyalty] redeemed {} pts (₹{} discount) for customer {} booking {}",
                burned, applied, customerId, bookingRef);
            return new RedemptionResult(burned, applied);
        } catch (RuntimeException ex) {
            log.warn("[loyalty] redeem failed for booking {} — {}", bookingRef, ex.getMessage());
            return new RedemptionResult(0, BigDecimal.ZERO);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ADMIN: manual adjustment (credit / debit)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public LoyaltyAccountDto adjustPoints(Long customerId, long points, String description, String role) {
        boolean isSuperAdmin = isSuperAdminRole(role);
        if (!isSuperAdmin && Math.abs(points) > MAX_SINGLE_ADJUSTMENT) {
            throw new BusinessException(
                "Single adjustment cannot exceed " + MAX_SINGLE_ADJUSTMENT + " points. "
                + "Only SUPER_ADMIN can perform larger adjustments.");
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
        String idempotencyKey = "adjust:" + customerId + ":" + now.toEpochSecond(ZoneOffset.UTC) + ":" + points;
        String actorRole = isSuperAdmin ? "SUPER_ADMIN" : "ADMIN";

        if (points > 0) {
            walletService.credit(new PointsWalletService.CreditRequest(
                m.getId(),
                points,
                LoyaltyV2Constants.LEDGER_ADJUST,
                "ADMIN_ADJUSTMENT",
                "adjust:customer=" + customerId,
                null,
                null,
                now,
                now.plusYears(10),
                idempotencyKey,
                null,
                "ADMIN_ADJUSTMENT",
                desc,
                null,
                actorRole
            ));
        } else if (points < 0) {
            walletService.debit(new PointsWalletService.DebitRequest(
                m.getId(),
                -points,
                LoyaltyV2Constants.LEDGER_ADJUST,
                null,
                null,
                idempotencyKey,
                null,
                "ADMIN_ADJUSTMENT",
                desc,
                null,
                actorRole
            ));
        }
        return getAccount(customerId);
    }

    private static boolean isSuperAdminRole(String role) {
        return role != null && role.toUpperCase().contains("SUPER_ADMIN");
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════

    /** Project a v2 ledger entry onto the legacy v1 transaction DTO shape. */
    private static LoyaltyTransactionDto ledgerToV1Dto(LoyaltyLedgerEntry e) {
        return LoyaltyTransactionDto.builder()
            .id(e.getId())
            .bookingRef(e.getBookingRef())
            .type(mapLedgerType(e.getEntryType()))
            .points(e.getPointsDelta())
            .description(e.getDescription())
            .createdAt(e.getCreatedAt())
            .build();
    }

    /** Map v2 ledger entry types onto the legacy v1 vocabulary the UI expects. */
    private static String mapLedgerType(String entryType) {
        if (entryType == null) return "ADJUST";
        return switch (entryType) {
            case "EARN", "BONUS", "STATUS_MATCH_GRANT", "TRANSFER_IN" -> "EARN";
            case "REDEEM", "TRANSFER_OUT" -> "REDEEM";
            case "REVERSE_EARN" -> "EARN_REVERSAL";
            case "REVERSE_REDEEM" -> "REVERSAL";
            case "EXPIRE" -> "EXPIRE";
            default -> "ADJUST";
        };
    }

    /**
     * Compute "points to next tier" + "next tier code" against the v2 tier ladder.
     * Skips non-progressive tiers (CUSTOMER_PWN admin-only tier; lifetime-status
     * tiers like LIFETIME_PLATINUM that are earned via lifetime credits + years
     * held, not qualification credits) so the customer-facing snapshot only
     * advertises tiers reachable by accruing more qualifying credits.
     */
    private TierGap computeTierGap(Long programId, String currentTierCode, long currentQualifyingCredits) {
        try {
            List<LoyaltyTierDefinition> ladder = loyaltyConfigService.activeLadder(programId, LocalDateTime.now());
            int idx = -1;
            for (int i = 0; i < ladder.size(); i++) {
                if (ladder.get(i).getCode().equals(currentTierCode)) { idx = i; break; }
            }
            if (idx < 0) {
                return new TierGap(null, null);
            }
            for (int i = idx + 1; i < ladder.size(); i++) {
                LoyaltyTierDefinition next = ladder.get(i);
                if (!isProgressiveTier(next)) {
                    continue;
                }
                long threshold = next.getQualificationCreditsRequired();
                long gap = Math.max(0L, threshold - currentQualifyingCredits);
                return new TierGap(gap, next.getCode());
            }
            return new TierGap(null, null);
        } catch (RuntimeException ex) {
            log.debug("[loyalty] tier-gap fallback for tier {}: {}", currentTierCode, ex.getMessage());
            return new TierGap(null, null);
        }
    }

    /**
     * A tier is "progressive" (advertisable as the next goal) only if it is
     * earned by qualifying credits. Lifetime-status tiers and the internal
     * CUSTOMER_PWN admin tier are excluded.
     */
    private static boolean isProgressiveTier(LoyaltyTierDefinition t) {
        if (t == null || t.getCode() == null) return false;
        if (LoyaltyV2Constants.TIER_CUSTOMER_PWN.equals(t.getCode())) return false;
        if (t.getLifetimeCreditsRequired() != null) return false;
        if (t.getLifetimeYearsHeldRequired() != null) return false;
        return t.getQualificationCreditsRequired() > 0L;
    }

    private record TierGap(Long pointsToNext, String nextTierCode) { }

    public record RedemptionResult(long pointsRedeemed, BigDecimal discountAmount) {}
}
