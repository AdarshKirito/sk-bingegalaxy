package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddOnSelection {
    @NotNull(message = "Add-on ID is required")
    private Long addOnId;

    @Min(value = 1, message = "Add-on quantity must be at least 1")
    private int quantity;
}
