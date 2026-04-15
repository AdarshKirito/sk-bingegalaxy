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

    @NotNull(message = "Multiplier is required")
    @DecimalMin(value = "1.0", message = "Multiplier must be at least 1.0")
    @DecimalMax(value = "5.0", message = "Multiplier must not exceed 5.0")
    private BigDecimal multiplier;

    @Size(max = 100)
    private String label;

    private boolean active;
}
