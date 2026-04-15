package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.LoyaltyAccountDto;
import com.skbingegalaxy.booking.dto.LoyaltyTransactionDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.LoyaltyAccount;
import com.skbingegalaxy.booking.entity.LoyaltyTransaction;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.LoyaltyAccountRepository;
import com.skbingegalaxy.booking.repository.LoyaltyTransactionRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyService {

    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyTransactionRepository transactionRepository;
    private final BingeRepository bingeRepository;

    // ── Tier thresholds (total points ever earned) ───────────
    private static final Map<String, Long> TIER_THRESHOLDS = Map.of(
        "BRONZE", 0L,
        "SILVER", 5_000L,
        "GOLD", 20_000L,
        "PLATINUM", 50_000L
    );
    private static final List<String> TIER_ORDER = List.of("BRONZE", "SILVER", "GOLD", "PLATINUM");

    // ═══════════════════════════════════════════════════════════
    //  GET ACCOUNT
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public LoyaltyAccountDto getAccount(Long customerId) {
        Long bingeId = BingeContext.requireBingeId();
        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        if (binge == null || !binge.isLoyaltyEnabled()) {
            return null;
        }

        Optional<LoyaltyAccount> accountOpt = accountRepository.findByCustomerIdAndBingeId(customerId, bingeId);
        if (accountOpt.isEmpty()) {
            // Return an empty account DTO (customer has no history yet)
            return LoyaltyAccountDto.builder()
                .customerId(customerId)
                .bingeId(bingeId)
                .totalPointsEarned(0)
                .currentBalance(0)
                .tierLevel("BRONZE")
                .pointsToNextTier(TIER_THRESHOLDS.get("SILVER"))
                .nextTierLevel("SILVER")
                .recentTransactions(List.of())
                .build();
        }

        LoyaltyAccount account = accountOpt.get();
        List<LoyaltyTransactionDto> recent = transactionRepository
            .findByAccountIdOrderByCreatedAtDesc(account.getId(), PageRequest.of(0, 10))
            .map(this::toTransactionDto)
            .getContent();

        return toAccountDto(account, recent);
    }

    // ═══════════════════════════════════════════════════════════
    //  EARN POINTS (called on booking checkout/completion)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public long earnPoints(Long customerId, Long bingeId, String bookingRef, BigDecimal amountPaid) {
        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        if (binge == null || !binge.isLoyaltyEnabled() || binge.getLoyaltyPointsPerRupee() <= 0) {
            return 0;
        }

        LoyaltyAccount account = getOrCreateAccount(customerId, bingeId);

        // Prevent double-earning for the same booking
        if (transactionRepository.existsByAccountIdAndBookingRefAndType(account.getId(), bookingRef, "EARN")) {
            log.debug("Points already earned for booking {} — skipping", bookingRef);
            return 0;
        }

        long points = amountPaid.multiply(BigDecimal.valueOf(binge.getLoyaltyPointsPerRupee()))
            .setScale(0, RoundingMode.FLOOR).longValue();
        if (points <= 0) return 0;

        account.setTotalPointsEarned(account.getTotalPointsEarned() + points);
        account.setCurrentBalance(account.getCurrentBalance() + points);
        recalculateTier(account);
        accountRepository.save(account);

        transactionRepository.save(LoyaltyTransaction.builder()
            .accountId(account.getId())
            .bookingRef(bookingRef)
            .type("EARN")
            .points(points)
            .description("Earned " + points + " points for booking " + bookingRef)
            .build());

        log.info("Loyalty: customer {} earned {} points for booking {} (new balance: {})",
            customerId, points, bookingRef, account.getCurrentBalance());
        return points;
    }

    // ═══════════════════════════════════════════════════════════
    //  REDEEM POINTS (called during booking creation)
    // ═══════════════════════════════════════════════════════════

    /**
     * Attempts to redeem the requested number of loyalty points.
     * Returns the actual discount amount in ₹.
     */
    @Transactional
    public RedemptionResult redeemPoints(Long customerId, Long bingeId, String bookingRef,
                                          long requestedPoints, BigDecimal maxDiscount) {
        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        if (binge == null || !binge.isLoyaltyEnabled() || binge.getLoyaltyRedemptionRate() <= 0) {
            return new RedemptionResult(0, BigDecimal.ZERO);
        }

        Optional<LoyaltyAccount> accountOpt = accountRepository.findByCustomerIdAndBingeId(customerId, bingeId);
        if (accountOpt.isEmpty() || accountOpt.get().getCurrentBalance() <= 0) {
            return new RedemptionResult(0, BigDecimal.ZERO);
        }

        LoyaltyAccount account = accountOpt.get();
        long actualPoints = Math.min(requestedPoints, account.getCurrentBalance());
        if (actualPoints <= 0) return new RedemptionResult(0, BigDecimal.ZERO);

        // Calculate discount: points / redemptionRate = ₹
        BigDecimal discount = BigDecimal.valueOf(actualPoints)
            .divide(BigDecimal.valueOf(binge.getLoyaltyRedemptionRate()), 2, RoundingMode.FLOOR);

        // Cap discount at the booking total
        if (discount.compareTo(maxDiscount) > 0) {
            discount = maxDiscount;
            actualPoints = discount.multiply(BigDecimal.valueOf(binge.getLoyaltyRedemptionRate()))
                .setScale(0, RoundingMode.CEILING).longValue();
        }

        if (actualPoints <= 0 || discount.compareTo(BigDecimal.ZERO) <= 0) {
            return new RedemptionResult(0, BigDecimal.ZERO);
        }

        account.setCurrentBalance(account.getCurrentBalance() - actualPoints);
        accountRepository.save(account);

        transactionRepository.save(LoyaltyTransaction.builder()
            .accountId(account.getId())
            .bookingRef(bookingRef)
            .type("REDEEM")
            .points(-actualPoints)
            .description("Redeemed " + actualPoints + " points (₹" + discount + " discount) for booking " + bookingRef)
            .build());

        log.info("Loyalty: customer {} redeemed {} points (₹{} discount) for booking {}",
            customerId, actualPoints, discount, bookingRef);
        return new RedemptionResult(actualPoints, discount);
    }

    /**
     * Reverse a previous redemption (e.g. on booking cancellation).
     */
    @Transactional
    public void reverseRedemption(Long customerId, Long bingeId, String bookingRef, long points) {
        if (points <= 0) return;
        Optional<LoyaltyAccount> accountOpt = accountRepository.findByCustomerIdAndBingeId(customerId, bingeId);
        if (accountOpt.isEmpty()) return;

        LoyaltyAccount account = accountOpt.get();
        account.setCurrentBalance(account.getCurrentBalance() + points);
        accountRepository.save(account);

        transactionRepository.save(LoyaltyTransaction.builder()
            .accountId(account.getId())
            .bookingRef(bookingRef)
            .type("ADJUST")
            .points(points)
            .description("Refunded " + points + " points for cancelled booking " + bookingRef)
            .build());
    }

    // ═══════════════════════════════════════════════════════════
    //  ADMIN: Manual adjustment
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public LoyaltyAccountDto adjustPoints(Long customerId, long points, String description) {
        Long bingeId = BingeContext.requireBingeId();
        LoyaltyAccount account = getOrCreateAccount(customerId, bingeId);

        if (points < 0 && account.getCurrentBalance() + points < 0) {
            throw new BusinessException("Insufficient loyalty balance for this adjustment");
        }

        account.setCurrentBalance(account.getCurrentBalance() + points);
        if (points > 0) {
            account.setTotalPointsEarned(account.getTotalPointsEarned() + points);
        }
        recalculateTier(account);
        accountRepository.save(account);

        transactionRepository.save(LoyaltyTransaction.builder()
            .accountId(account.getId())
            .type("ADJUST")
            .points(points)
            .description(description != null ? description : "Admin adjustment")
            .build());

        return toAccountDto(account, List.of());
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════

    private LoyaltyAccount getOrCreateAccount(Long customerId, Long bingeId) {
        return accountRepository.findByCustomerIdAndBingeId(customerId, bingeId)
            .orElseGet(() -> accountRepository.save(LoyaltyAccount.builder()
                .customerId(customerId)
                .bingeId(bingeId)
                .build()));
    }

    private void recalculateTier(LoyaltyAccount account) {
        long earned = account.getTotalPointsEarned();
        String tier = "BRONZE";
        for (int i = TIER_ORDER.size() - 1; i >= 0; i--) {
            if (earned >= TIER_THRESHOLDS.get(TIER_ORDER.get(i))) {
                tier = TIER_ORDER.get(i);
                break;
            }
        }
        account.setTierLevel(tier);
    }

    private LoyaltyAccountDto toAccountDto(LoyaltyAccount account, List<LoyaltyTransactionDto> recent) {
        int tierIdx = TIER_ORDER.indexOf(account.getTierLevel());
        Long pointsToNext = null;
        String nextTier = null;
        if (tierIdx < TIER_ORDER.size() - 1) {
            nextTier = TIER_ORDER.get(tierIdx + 1);
            pointsToNext = TIER_THRESHOLDS.get(nextTier) - account.getTotalPointsEarned();
            if (pointsToNext < 0) pointsToNext = 0L;
        }

        return LoyaltyAccountDto.builder()
            .id(account.getId())
            .customerId(account.getCustomerId())
            .bingeId(account.getBingeId())
            .totalPointsEarned(account.getTotalPointsEarned())
            .currentBalance(account.getCurrentBalance())
            .tierLevel(account.getTierLevel())
            .pointsToNextTier(pointsToNext)
            .nextTierLevel(nextTier)
            .createdAt(account.getCreatedAt())
            .updatedAt(account.getUpdatedAt())
            .recentTransactions(recent)
            .build();
    }

    private LoyaltyTransactionDto toTransactionDto(LoyaltyTransaction t) {
        return LoyaltyTransactionDto.builder()
            .id(t.getId())
            .bookingRef(t.getBookingRef())
            .type(t.getType())
            .points(t.getPoints())
            .description(t.getDescription())
            .createdAt(t.getCreatedAt())
            .build();
    }

    public record RedemptionResult(long pointsRedeemed, BigDecimal discountAmount) {}
}
