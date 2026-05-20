package com.skbingegalaxy.booking.tax.provider;

import com.skbingegalaxy.booking.dto.TaxComputationResult;

import java.math.BigDecimal;

/**
 * Strategy interface that lets the booking service swap its tax engine
 * (internal rule engine, Stripe Tax, Avalara, TaxJar, Vertex …) without
 * touching {@code BookingService}.
 *
 * <p>{@link com.skbingegalaxy.booking.tax.provider.InternalTaxProvider} is
 * the default implementation. External providers can be added later by
 * implementing this interface and marking the bean as {@code @Primary}, or
 * by routing through a dispatcher based on country/binge configuration.
 */
public interface TaxProvider {

    /** Stable identifier returned in the {@code provider} field of the result. */
    String name();

    /**
     * Compute taxes for the supplied taxable inputs in the given jurisdiction.
     *
     * @param ctx jurisdiction + customer-type + product-type bag
     * @param subtotal      total taxable amount in BASE currency
     * @param baseAmount    portion of subtotal treated as the "base" rate component
     * @param addOnAmount   portion attributable to add-ons
     * @param guestAmount   portion attributable to guest fees
     */
    TaxComputationResult computeTaxes(TaxContext ctx,
                                      BigDecimal subtotal,
                                      BigDecimal baseAmount,
                                      BigDecimal addOnAmount,
                                      BigDecimal guestAmount);
}
