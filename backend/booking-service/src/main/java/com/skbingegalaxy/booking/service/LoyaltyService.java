package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.config.LoyaltyProperties;
import com.skbingegalaxy.booking.dto.LoyaltyAccountDto;
import com.skbingegalaxy.booking.dto.LoyaltyTransactionDto;
import com.skbingegalaxy.booking.entity.LoyaltyAccount;
import com.skbingegalaxy.booking.entity.LoyaltyTransaction;
import com.skbingegalaxy.booking.repository.LoyaltyAccountRepository;
import com.skbingegalaxy.booking.repository.LoyaltyTransactionRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyService {

    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyTransactionRepository transactionRepository;
    private final LoyaltyProperties loyaltyProps;

    // ── Tier thresholds (total points ever earned) ───────────
    private static final Map<String, Long> TIER_THRESHOLDS = Map.of(
        "BRONZE", 0L,
        "SILVER", 5_000L,
        "GOLD", 20_000L,
        "PLATINUM", 50_000L
    );
    private static final List<String> TIER_ORDER = List.of("BRONZE", "SILVER", "GOLD", "PLATINUM");

    /** Points expire after 365 days by default. */
    private static final int POINTS_EXPIRY_DAYS = 365;

    // ═══════════════════════════════════════════════════════════
    //  GET ACCOUNT
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public LoyaltyAccountDto getAccount(Long customerId) {
        if (!loyaltyProps.isEnabled()) {
            return null;
        }

        Optional<LoyaltyAccount> accountOpt = accountRepository.findByCustomerId(customerId);
        if (accountOpt.isEmpty()) {
            // Return an empty account DTO (customer has no history yet)
            return LoyaltyAccountDto.builder()
                .customerId(customerId)
                .totalPointsEarned(0)
                .currentBalance(0)
                .tierLevel("BRONZE")
                .pointsToNextTier(TIER_THRESHOLDS.get("SILVER"))
                .nextTierLevel("SILVER")
                .redemptionRate(loyaltyProps.getRedemptionRate())
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
    public long earnPoints(Long customerId, String bookingRef, BigDecimal amountPaid) {
        if (!loyaltyProps.isEnabled() || loyaltyProps.getPointsPerRupee() <= 0) {
            return 0;
        }

        LoyaltyAccount account = getOrCreateAccount(customerId);

        // Prevent double-earning for the same booking
        if (transactionRepository.existsByAccountIdAndBookingRefAndType(account.getId(), bookingRef, "EARN")) {
            log.debug("Points already earned for booking {} — skipping", bookingRef);
            return 0;
        }

        long points = amountPaid.multiply(BigDecimal.valueOf(loyaltyProps.getPointsPerRupee()))
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
            .expiresAt(LocalDateTime.now().plusDays(POINTS_EXPIRY_DAYS))
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
    public RedemptionResult redeemPoints(Long customerId, String bookingRef,
                                          long requestedPoints, BigDecimal maxDiscount) {
        if (!loyaltyProps.isEnabled() || loyaltyProps.getRedemptionRate() <= 0) {
            return new RedemptionResult(0, BigDecimal.ZERO);
        }

        Optional<LoyaltyAccount> accountOpt = accountRepository.findByCustomerIdForUpdate(customerId);
        if (accountOpt.isEmpty() || accountOpt.get().getCurrentBalance() <= 0) {
            return new RedemptionResult(0, BigDecimal.ZERO);
        }

        LoyaltyAccount account = accountOpt.get();
        long actualPoints = Math.min(requestedPoints, account.getCurrentBalance());
        if (actualPoints <= 0) return new RedemptionResult(0, BigDecimal.ZERO);

        BigDecimal discount = BigDecimal.valueOf(actualPoints)
            .divide(BigDecimal.valueOf(loyaltyProps.getRedemptionRate()), 2, RoundingMode.FLOOR);

        // Cap discount at the booking total
        if (discount.compareTo(maxDiscount) > 0) {
            discount = maxDiscount;
            actualPoints = discount.multiply(BigDecimal.valueOf(loyaltyProps.getRedemptionRate()))
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
    public void reverseRedemption(Long customerId, String bookingRef, long points) {
        if (points <= 0) return;
        Optional<LoyaltyAccount> accountOpt = accountRepository.findByCustomerIdForUpdate(customerId);
        if (accountOpt.isEmpty()) return;

        LoyaltyAccount account = accountOpt.get();

        // Idempotency: skip if already reversed for this booking
        if (transactionRepository.existsByAccountIdAndBookingRefAndType(account.getId(), bookingRef, "REVERSAL")) {
            log.info("Loyalty reversal already processed for booking {} — skipping", bookingRef);
            return;
        }

        account.setCurrentBalance(account.getCurrentBalance() + points);
        accountRepository.save(account);

        transactionRepository.save(LoyaltyTransaction.builder()
            .accountId(account.getId())
            .bookingRef(bookingRef)
            .type("REVERSAL")
            .points(points)
            .description("Refunded " + points + " points for cancelled booking " + bookingRef)
            .build());
    }

    // ═══════════════════════════════════════════════════════════
    //  ADMIN: Manual adjustment
    // ═══════════════════════════════════════════════════════════

    private static final long MAX_SINGLE_ADJUSTMENT = 100_000L;

    @Transactional
    public LoyaltyAccountDto adjustPoints(Long customerId, long points, String description, String role) {
        LoyaltyAccount account = getOrCreateAccount(customerId);

        boolean isSuperAdmin = "SUPER_ADMIN".equalsIgnoreCase(role);
        if (!isSuperAdmin && Math.abs(points) > MAX_SINGLE_ADJUSTMENT) {
            throw new BusinessException(
                "Single adjustment cannot exceed " + MAX_SINGLE_ADJUSTMENT + " points. "
                + "Only SUPER_ADMIN can perform larger adjustments.");
        }

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
    //  EXPIRE POINTS (scheduled daily at 2:00 AM)
    // ═══════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "loyalty-expire-points", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void expirePoints() {
        List<LoyaltyTransaction> expired = transactionRepository.findExpiredEarnTransactions(LocalDateTime.now());
        int count = 0;
        for (LoyaltyTransaction earn : expired) {
            if (earn.getPoints() <= 0) continue;

            LoyaltyAccount account = accountRepository.findById(earn.getAccountId()).orElse(null);
            if (account == null) continue;

            long pointsToExpire = Math.min(earn.getPoints(), account.getCurrentBalance());
            if (pointsToExpire <= 0) {
                // Mark as expired even if balance is 0 (points were already redeemed)
                earn.setPoints(0);
                transactionRepository.save(earn);
                continue;
            }

            account.setCurrentBalance(account.getCurrentBalance() - pointsToExpire);
            accountRepository.save(account);

            transactionRepository.save(LoyaltyTransaction.builder()
                .accountId(account.getId())
                .bookingRef(earn.getBookingRef())
                .type("EXPIRE")
                .points(-pointsToExpire)
                .description("Expired " + pointsToExpire + " points (earned " + POINTS_EXPIRY_DAYS + "+ days ago)")
                .build());

            // Zero out the original EARN so it isn't processed again
            earn.setPoints(0);
            transactionRepository.save(earn);
            count++;
        }
        if (count > 0) {
            log.info("Loyalty expiry: expired points for {} transactions", count);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  REVERSE EARNED POINTS (on booking cancellation)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void reverseEarnedPoints(Long customerId, String bookingRef) {
        Optional<LoyaltyAccount> accountOpt = accountRepository.findByCustomerIdForUpdate(customerId);
        if (accountOpt.isEmpty()) return;

        LoyaltyAccount account = accountOpt.get();

        // Idempotency: skip if EARN_REVERSAL already recorded for this booking
        if (transactionRepository.existsByAccountIdAndBookingRefAndType(account.getId(), bookingRef, "EARN_REVERSAL")) {
            log.info("Earned points already reversed for booking {} — skipping", bookingRef);
            return;
        }

        long earnedForBooking = transactionRepository.sumEarnedPointsForBooking(account.getId(), bookingRef);
        if (earnedForBooking <= 0) return;

        long actualDeduction = Math.min(earnedForBooking, account.getCurrentBalance());
        if (actualDeduction > 0) {
            account.setCurrentBalance(account.getCurrentBalance() - actualDeduction);
        }
        account.setTotalPointsEarned(account.getTotalPointsEarned() - earnedForBooking);
        recalculateTier(account);
        accountRepository.save(account);

        transactionRepository.save(LoyaltyTransaction.builder()
            .accountId(account.getId())
            .bookingRef(bookingRef)
            .type("EARN_REVERSAL")
            .points(-actualDeduction)
            .description("Reversed " + earnedForBooking + " earned points for cancelled booking " + bookingRef)
            .build());

        log.info("Loyalty: reversed {} earned points (deducted {} from balance) for cancelled booking {}",
            earnedForBooking, actualDeduction, bookingRef);
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════

    private LoyaltyAccount getOrCreateAccount(Long customerId) {
        return accountRepository.findByCustomerId(customerId)
            .orElseGet(() -> accountRepository.save(LoyaltyAccount.builder()
                .customerId(customerId)
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
            .totalPointsEarned(account.getTotalPointsEarned())
            .currentBalance(account.getCurrentBalance())
            .tierLevel(account.getTierLevel())
            .pointsToNextTier(pointsToNext)
            .nextTierLevel(nextTier)
            .createdAt(account.getCreatedAt())
            .updatedAt(account.getUpdatedAt())
            .redemptionRate(loyaltyProps.getRedemptionRate())
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
