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
