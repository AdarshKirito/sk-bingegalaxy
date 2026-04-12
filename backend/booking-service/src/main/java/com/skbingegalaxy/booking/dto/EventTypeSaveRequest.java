package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventTypeSaveRequest {

    @NotBlank(message = "Event type name is required")
    @Size(max = 100, message = "Event type name must be under 100 characters")
    private String name;

    @Size(max = 500, message = "Description must be under 500 characters")
    private String description;

    @NotNull(message = "Base price is required")
    @DecimalMin(value = "0.0", message = "Base price cannot be negative")
    private BigDecimal basePrice;

    @NotNull(message = "Hourly rate is required")
    @DecimalMin(value = "0.0", message = "Hourly rate cannot be negative")
    private BigDecimal hourlyRate;

    @DecimalMin(value = "0.0", message = "Price per guest cannot be negative")
    private BigDecimal pricePerGuest = BigDecimal.ZERO;

    @Min(value = 1, message = "Minimum hours must be at least 1")
    private int minHours = 1;

    @Min(value = 1, message = "Maximum hours must be at least 1")
    @Max(value = 24, message = "Maximum hours cannot exceed 24")
    private int maxHours = 8;

    private List<String> imageUrls;
}
