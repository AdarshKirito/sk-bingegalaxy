package com.skbingegalaxy.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerPricingSaveRequest {
    @NotNull(message = "Customer ID is required")
    private Long customerId;
    private Long rateCodeId;    // nullable — assign/unassign rate code
    @Valid
    private List<EventPricingEntry> eventPricings;
    @Valid
    private List<AddonPricingEntry> addonPricings;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventPricingEntry {
        @NotNull(message = "Event type ID is required")
        private Long eventTypeId;
        @DecimalMin(value = "0.0", message = "Base price cannot be negative")
        private BigDecimal basePrice;
        @DecimalMin(value = "0.0", message = "Hourly rate cannot be negative")
        private BigDecimal hourlyRate;
        @DecimalMin(value = "0.0", message = "Price per guest cannot be negative")
        private BigDecimal pricePerGuest;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddonPricingEntry {
        @NotNull(message = "Add-on ID is required")
        private Long addOnId;
        @DecimalMin(value = "0.0", message = "Add-on price cannot be negative")
        private BigDecimal price;
    }
}
