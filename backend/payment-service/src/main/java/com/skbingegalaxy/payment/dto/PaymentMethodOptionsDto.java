package com.skbingegalaxy.payment.dto;

import com.skbingegalaxy.common.enums.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The payment rails offered for one booking, derived from the VENUE's country.
 *
 * <p>The checkout UI renders exactly what this returns instead of a hardcoded
 * list, which is what makes a US customer paying a Mumbai venue see UPI while an
 * Indian customer paying a New York venue does not.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodOptionsDto {

    /** ISO-4217 the booking is charged in (the venue's currency). */
    private String currency;

    /** ISO-3166 alpha-2 of the venue; null on legacy venues without a country. */
    private String venueCountry;

    /** Gateway that will handle the charge — informational for support/debugging. */
    private String provider;

    /** Rail the UI should pre-select (first entry of {@link #methods}). */
    private PaymentMethod defaultMethod;

    /** Offerable rails in local-preference order. Never contains CASH. */
    private List<Option> methods;

    /** One selectable rail plus its market-appropriate display label. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Option {
        /** Enum value to send back on {@code POST /payments/initiate}. */
        private PaymentMethod method;
        /** Country-aware label, e.g. "Net banking" (IN) vs "Bank transfer (ACH)" (US). */
        private String label;
    }
}
