package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SurgePricingRuleDto {
    private Long id;
    private Long bingeId;
    private String name;
    private Integer dayOfWeek;
    private int startMinute;
    private int endMinute;
    private BigDecimal multiplier;
    private String label;
    private java.time.LocalDate dateFrom;
    private java.time.LocalDate dateTo;
    private Integer leadTimeMaxHours;
    private Integer leadTimeMinHours;
    private Integer occupancyThresholdPct;
    private Integer priority;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
