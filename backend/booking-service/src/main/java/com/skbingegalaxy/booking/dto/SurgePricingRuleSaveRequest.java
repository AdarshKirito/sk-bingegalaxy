package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SurgePricingRuleSaveRequest {

    @NotBlank(message = "Rule name is required")
    @Size(max = 100)
    private String name;

    /** Day of week: 1=Monday ... 7=Sunday. Null = all days. */
    @Min(1) @Max(7)
    private Integer dayOfWeek;

    @Min(value = 0, message = "Start minute must be >= 0")
    @Max(value = 1439, message = "Start minute must be < 1440")
    private int startMinute;

    @Min(value = 1, message = "End minute must be > 0")
    @Max(value = 1440, message = "End minute must be <= 1440")
    private int endMinute;

    /** Below 1.0 = early-bird/off-peak discount; above 1.0 = surge premium. */
    @NotNull(message = "Multiplier is required")
    @DecimalMin(value = "0.1", message = "Multiplier must be at least 0.1")
    @DecimalMax(value = "5.0", message = "Multiplier must not exceed 5.0")
    private BigDecimal multiplier;

    @Size(max = 100)
    private String label;

    /** Seasonal/event window (inclusive). Null = no date restriction. */
    private java.time.LocalDate dateFrom;
    private java.time.LocalDate dateTo;

    /** Last-minute premium: applies when the booking starts within X hours. */
    @Min(0) @Max(8760)
    private Integer leadTimeMaxHours;

    /** Early-bird: applies when booked at least X hours ahead. */
    @Min(0) @Max(8760)
    private Integer leadTimeMinHours;

    /** Demand trigger: applies once the date is ≥ X% booked. */
    @Min(1) @Max(100)
    private Integer occupancyThresholdPct;

    /** Winner among overlapping rules: lowest number wins. */
    @Min(1) @Max(1000)
    private Integer priority;

    private boolean active;
}
