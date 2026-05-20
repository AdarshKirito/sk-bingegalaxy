package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/bookings/checkout/preview}.
 * Mirrors the subset of {@link CreateBookingRequest} fields needed to quote
 * a price + tax. All fields are optional; the service falls back to defaults
 * when a value is missing (e.g. anonymous preview).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutPreviewRequest {
    private Long bingeId;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private Integer durationMinutes;
    private Integer numberOfGuests;
    private List<Long> addOnIds;
    private Long packageId;

    /** Display currency the user has selected. */
    private String displayCurrencyCode;
    /** Currency the customer wants to be charged in. Defaults to display. */
    private String paymentCurrencyCode;

    /** Optional billing address for tax-jurisdiction resolution. */
    private BillingAddressDto billingAddress;
    /** B2C / B2B. */
    private String customerType;

    /** Optional loyalty redemption to preview. */
    private Integer redeemLoyaltyPoints;
    /** Optional coupon to preview. */
    private String couponCode;
}
