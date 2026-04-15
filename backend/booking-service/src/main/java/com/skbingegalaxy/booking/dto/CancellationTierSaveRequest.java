package com.skbingegalaxy.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CancellationTierSaveRequest {

    @Valid
    @Size(max = 10, message = "A maximum of 10 cancellation tiers are allowed")
    private List<TierEntry> tiers;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierEntry {
        @Min(value = 0, message = "Hours must be >= 0")
        private int hoursBeforeStart;

        @Min(value = 0, message = "Refund percentage must be 0-100")
        @Max(value = 100, message = "Refund percentage must be 0-100")
        private int refundPercentage;

        @Size(max = 100)
        private String label;
    }
}
