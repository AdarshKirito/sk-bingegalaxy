package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Quote returned by {@code GET /api/v1/bookings/checkout/preview}.
 * Carries the entire price breakdown so the frontend can render a
 * production-grade checkout page without recomputing anything.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutPreviewResponse {

    /** Currency we calculate in. Always INR for now. */
    private String baseCurrencyCode;
    /** Currency rendered to the user. */
    private String displayCurrencyCode;
    /** Currency that will actually be charged at the gateway. */
    private String paymentCurrencyCode;

    private BigDecimal subtotalBase;
    private BigDecimal surgeAmountBase;
    private BigDecimal discountAmountBase;
    private BigDecimal loyaltyRedemptionBase;
    private BigDecimal platformFeeBase;
    private BigDecimal taxAmountBase;
    private BigDecimal totalBase;

    private BigDecimal displayTotal;
    private BigDecimal paymentTotal;

    private BigDecimal fxRateDisplay;
    private BigDecimal fxRatePayment;
    private String fxSource;

    /** Optional FX lock token for stable pricing. */
    private String fxLockId;
    private LocalDateTime fxLockedUntil;

    private TaxComputationResult tax;
    private List<TaxComputationResult.TaxLine> taxLines;

    /** Breakdown items keyed for UI rendering. */
    private List<BreakdownItem> breakdown;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BreakdownItem {
        private String key;       // SUBTOTAL, SURGE, LOYALTY, TAX_LINE_<n>, PLATFORM_FEE, TOTAL
        private String label;
        private BigDecimal amountBase;
        private BigDecimal amountDisplay;
    }
}
