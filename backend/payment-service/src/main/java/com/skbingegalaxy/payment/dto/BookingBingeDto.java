package com.skbingegalaxy.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingBingeDto {
    private Long id;
    private Long adminId;
    private boolean active;
    /**
     * ISO-3166 alpha-2 country of the VENUE. Booking-service has always sent this on
     * /internal/binges/{id}; it was previously dropped here by @JsonIgnoreProperties,
     * so payment-service could not see where a venue actually was. It now drives
     * Stripe Connect onboarding (the connected account's country is immutable once
     * created) and payment-method resolution.
     */
    private String country;
    /** ISO-4217 settlement currency, derived from {@link #country} by booking-service. */
    private String currency;
    /** V71 module matrix: modules the requesting user may NOT use here (null when no userId sent). */
    private java.util.List<String> deniedModules;
}