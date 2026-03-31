package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resolved pricing for a customer — returned to the frontend so it can show
 * the correct prices in the booking wizard.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolvedPricingDto {
    private Long customerId;
    private String pricingSource;     // DEFAULT, RATE_CODE, CUSTOMER
    private String rateCodeName;      // null when DEFAULT or CUSTOMER

    private List<EventPricing> eventPricings;
    private List<AddonPricing> addonPricings;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventPricing {
        private Long eventTypeId;
        private String eventTypeName;
        private BigDecimal basePrice;
        private BigDecimal hourlyRate;
        private BigDecimal pricePerGuest;
        private String source;   // DEFAULT, RATE_CODE, CUSTOMER
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddonPricing {
        private Long addOnId;
        private String addOnName;
        private BigDecimal price;
        private String source;   // DEFAULT, RATE_CODE, CUSTOMER
    }
}
