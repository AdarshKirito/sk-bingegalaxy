package com.skbingegalaxy.booking.dto;

import lombok.*;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardStatsDto {
    private long totalBookings;
    private long pendingBookings;
    private long confirmedBookings;
    private long cancelledBookings;
    private long completedBookings;
    private BigDecimal totalRevenue;
    // Today-specific stats
    private long todayTotal;
    private long todayConfirmed;
    private long todayCheckedIn;
    private long todayPending;
    private long todayCompleted;
    private BigDecimal todayRevenue;            // actual: COMPLETED + payment SUCCESS
    private BigDecimal todayEstimatedRevenue;   // all non-cancelled today's bookings
}
