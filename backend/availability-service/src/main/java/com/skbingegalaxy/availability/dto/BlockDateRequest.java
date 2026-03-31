package com.skbingegalaxy.availability.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockDateRequest {
    @NotNull(message = "Date is required")
    private LocalDate date;
    private String reason;
}
