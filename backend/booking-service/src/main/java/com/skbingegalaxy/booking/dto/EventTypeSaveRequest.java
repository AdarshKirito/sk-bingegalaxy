package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventTypeSaveRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 500)
    private String description;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal basePrice;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal hourlyRate;

    @DecimalMin("0.0")
    private BigDecimal pricePerGuest = BigDecimal.ZERO;

    @Min(1)
    private int minHours = 1;

    @Min(1) @Max(24)
    private int maxHours = 8;

    private List<String> imageUrls;
}
