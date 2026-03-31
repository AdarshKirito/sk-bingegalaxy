package com.skbingegalaxy.booking.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportDto {
    private LocalDate fromDate;
    private LocalDate toDate;
    private String period; // DAY, WEEK, MONTH, YEAR
    private long totalBookings;
    private BigDecimal totalRevenue;       // actual: COMPLETED + payment SUCCESS
    private BigDecimal estimatedRevenue;   // all non-cancelled bookings
}
