package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateFreezeRequest {

    @NotNull(message = "customerId is required")
    private Long customerId;

    @NotNull(message = "bingeId is required")
    private Long bingeId;

    @NotNull(message = "durationMinutes is required")
    @Min(value = 1, message = "durationMinutes must be at least 1")
    private Integer durationMinutes;

    @Size(max = 1000)
    private String reason;
}
