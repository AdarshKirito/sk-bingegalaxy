package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RateCodeSaveRequest {

    @NotBlank(message = "Rate code name is required")
    private String name;

    private String description;

    private List<EventPricingEntry> eventPricings;
    private List<AddonPricingEntry> addonPricings;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventPricingEntry {
        private Long eventTypeId;
        private BigDecimal basePrice;
        private BigDecimal hourlyRate;
        private BigDecimal pricePerGuest;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddonPricingEntry {
        private Long addOnId;
        private BigDecimal price;
    }
}
