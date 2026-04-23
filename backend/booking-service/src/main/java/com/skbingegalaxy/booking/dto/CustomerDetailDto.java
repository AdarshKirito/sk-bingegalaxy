package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerDetailDto {

    private Long customerId;

    // current rate code info
    private Long currentRateCodeId;
    private String currentRateCodeName;
    private String memberLabel;

    // ── Membership / loyalty snapshot (populated from LoyaltyService) ──
    /** BRONZE / SILVER / GOLD / PLATINUM (null when loyalty is disabled) */
    private String memberTier;
    /** Current redeemable points balance */
    private Long loyaltyPoints;
    /** Lifetime points earned (never decays for tier calculation) */
    private Long lifetimePointsEarned;
    /** Points needed to reach next tier; null when already at max tier */
    private Long pointsToNextTier;
    private String nextTierLevel;
    /** When the customer first joined the loyalty program */
    private LocalDateTime memberSince;

    // ── Review signals (populated from BookingReviewRepository) ──
    /** Average rating admins have given this customer (1–5). 0 when none yet. */
    private Double avgAdminRating;
    /** Number of admin reviews submitted on this customer */
    private Long adminReviewCount;
    /** Number of customer reviews this customer has submitted */
    private Long customerReviewCount;
    /** Weighted influence this customer's reviews will have (0.5–1.25). */
    private Double reviewWeight;

    // ── Spend summary (across this binge) ──
    private BigDecimal lifetimeSpend;
    private BigDecimal pendingBalance;

    // rate code change audit trail
    private List<RateCodeChange> rateCodeChanges;

    // reservation summary for this binge
    private long totalReservations;
    private List<ReservationSummary> reservations;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RateCodeChange {
        private Long id;
        private String previousRateCodeName;
        private String newRateCodeName;
        private String changeType;
        private Long changedByAdminId;
        private LocalDateTime changedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReservationSummary {
        private String bookingRef;
        private String eventTypeName;
        private LocalDate bookingDate;
        private LocalTime startTime;
        private int durationMinutes;
        private String status;
        private String paymentStatus;
        private BigDecimal totalAmount;
        private BigDecimal collectedAmount;
        private String pricingSource;
        private String rateCodeName;
        private LocalDateTime createdAt;
    }
}
