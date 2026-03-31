package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RateCodeDto {
    private Long id;
    private String name;
    private String description;
    private boolean active;
    private List<EventPricingEntry> eventPricings;
    private List<AddonPricingEntry> addonPricings;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventPricingEntry {
        private Long eventTypeId;
        private String eventTypeName;
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
        private String addOnName;
        private BigDecimal price;
    }
}
