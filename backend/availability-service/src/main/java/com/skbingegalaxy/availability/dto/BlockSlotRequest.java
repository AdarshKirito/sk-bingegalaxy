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

    @Min(value = 0, message = "Start minute must be >= 0")
    @Max(value = 1410)
    private int startHour;

    @Min(value = 30, message = "End minute must be >= 30")
    @Max(value = 1440)
    private int endHour;

    private String reason;
}
