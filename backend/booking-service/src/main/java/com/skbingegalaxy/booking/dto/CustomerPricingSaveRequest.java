package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerPricingSaveRequest {
    private Long customerId;
    private Long rateCodeId;    // nullable — assign/unassign rate code
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
