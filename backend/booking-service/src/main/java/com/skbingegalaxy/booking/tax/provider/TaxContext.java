package com.skbingegalaxy.booking.tax.provider;

import lombok.Builder;
import lombok.Data;

/**
 * Inputs needed to resolve a tax jurisdiction. All fields are optional so
 * the provider can fall back to defaults when only partial data is known
 * (e.g. anonymous checkout preview).
 */
@Data
@Builder
public class TaxContext {
    /** Binge whose pricing this booking attaches to (used for binge-scoped rules). */
    private Long bingeId;

    /** Where the venue is located. Used as a fallback when billing is unknown. */
    private String venueCountryCode;
    private String venueStateCode;
    private String venueCity;
    private String venuePostalCode;

    /** Customer's billing country/state/city/postal code. Wins over venue when set. */
    private String billingCountryCode;
    private String billingStateCode;
    private String billingCity;
    private String billingPostalCode;

    /** B2C, B2B, or null. */
    private String customerType;

    /** BOOKING, ADDON, GUEST_FEE, or ALL. */
    private String productType;

    /** When TRUE the customer supplied a tax id (GSTIN / VAT) — may unlock B2B rules. */
    private boolean buyerHasTaxId;

    public String resolvedCountry() {
        return billingCountryCode != null && !billingCountryCode.isBlank()
            ? billingCountryCode : venueCountryCode;
    }

    public String resolvedState() {
        return billingStateCode != null && !billingStateCode.isBlank()
            ? billingStateCode : venueStateCode;
    }

    public String resolvedCity() {
        return billingCity != null && !billingCity.isBlank() ? billingCity : venueCity;
    }

    public String resolvedPostal() {
        return billingPostalCode != null && !billingPostalCode.isBlank()
            ? billingPostalCode : venuePostalCode;
    }
}
