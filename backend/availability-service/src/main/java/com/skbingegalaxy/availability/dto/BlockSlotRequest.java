package com.skbingegalaxy.availability.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockSlotRequest {
    @NotNull(message = "Date is required")
    private LocalDate date;

    @Min(value = 0, message = "Start hour must be between 0 and 23")
    @Max(value = 23)
    private int startHour;

    @Min(value = 1, message = "End hour must be between 1 and 24")
    @Max(value = 24)
    private int endHour;

    private String reason;
}
