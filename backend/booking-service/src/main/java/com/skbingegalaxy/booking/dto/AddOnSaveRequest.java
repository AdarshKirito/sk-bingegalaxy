package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddOnSaveRequest {

    @NotBlank(message = "Add-on name is required")
    @Size(max = 100, message = "Add-on name must be under 100 characters")
    private String name;

    @Size(max = 300, message = "Description must be under 300 characters")
    private String description;

    @NotNull(message = "Add-on price is required")
    @DecimalMin(value = "0.0", message = "Add-on price cannot be negative")
    private BigDecimal price;

    @NotBlank(message = "Add-on category is required")
    @Size(max = 50, message = "Category must be under 50 characters")
    private String category;

    private List<String> imageUrls;

    /** Daily inventory cap. NULL = unlimited. Negative values rejected by DB CHECK. */
    @Min(value = 0, message = "Stock per day cannot be negative")
    private Integer stockPerDay;

    /** Minimum advance-notice minutes before booking start. NULL = none. */
    @Min(value = 0, message = "Advance notice cannot be negative")
    private Integer advanceNoticeMinutes;
}
