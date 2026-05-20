package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable snapshot of every monetary detail at the moment a price was
 * quoted or accepted. Used to:
 *
 * <ul>
 *   <li>Power reproducible invoices and credit notes.</li>
 *   <li>Compute deterministic refunds even after FX rates have moved.</li>
 *   <li>Provide an audit trail for financial reconciliation.</li>
 * </ul>
 *
 * The matching DB-side trigger {@code fn_block_snapshot_mutation()} forbids
 * UPDATE / DELETE operations — the only way to "change" a snapshot is to
 * create a new row.
 */
@Entity
@Table(name = "booking_price_snapshots", indexes = {
    @Index(name = "idx_bps_booking_ref", columnList = "booking_ref"),
    @Index(name = "idx_bps_customer", columnList = "customer_id"),
    @Index(name = "idx_bps_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
public class BookingPriceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_ref", length = 64)
    private String bookingRef;

    @Column(name = "binge_id")
    private Long bingeId;

    @Column(name = "customer_id")
    private Long customerId;

    // ── Currency triple ────────────────────────────────────────────────
    @Column(name = "base_currency_code", nullable = false, length = 8)
    private String baseCurrencyCode;

    @Column(name = "display_currency_code", length = 8)
    private String displayCurrencyCode;

    @Column(name = "payment_currency_code", length = 8)
    private String paymentCurrencyCode;

    @Column(name = "settlement_currency_code", length = 8)
    private String settlementCurrencyCode;

    // ── Money columns (4 decimals — precise enough for FX-converted minor units) ──
    @Column(name = "subtotal_base", precision = 14, scale = 4)
    private BigDecimal subtotalBase;

    @Column(name = "surge_amount_base", precision = 14, scale = 4)
    private BigDecimal surgeAmountBase;

    @Column(name = "discount_amount_base", precision = 14, scale = 4)
    private BigDecimal discountAmountBase;

    @Column(name = "loyalty_redemption_base", precision = 14, scale = 4)
    private BigDecimal loyaltyRedemptionBase;

    @Column(name = "platform_fee_base", precision = 14, scale = 4)
    private BigDecimal platformFeeBase;

    @Column(name = "tax_amount_base", precision = 14, scale = 4)
    private BigDecimal taxAmountBase;

    @Column(name = "total_base", precision = 14, scale = 4)
    private BigDecimal totalBase;

    @Column(name = "display_total", precision = 14, scale = 4)
    private BigDecimal displayTotal;

    @Column(name = "payment_total", precision = 14, scale = 4)
    private BigDecimal paymentTotal;

    // ── FX rates ──────────────────────────────────────────────────────
    @Column(name = "fx_rate_display", precision = 20, scale = 10)
    private BigDecimal fxRateDisplay;

    @Column(name = "fx_rate_payment", precision = 20, scale = 10)
    private BigDecimal fxRatePayment;

    @Column(name = "fx_rate_settlement", precision = 20, scale = 10)
    private BigDecimal fxRateSettlement;

    @Column(name = "fx_source", length = 40)
    private String fxSource;

    @Column(name = "fx_locked_at")
    private LocalDateTime fxLockedAt;

    @Column(name = "fx_locked_until")
    private LocalDateTime fxLockedUntil;

    // ── Breakdown JSON (canonical for invoices) ───────────────────────
    @Column(name = "tax_breakdown_json", columnDefinition = "TEXT")
    private String taxBreakdownJson;

    @Column(name = "pricing_breakdown_json", columnDefinition = "TEXT")
    private String pricingBreakdownJson;

    // ── Jurisdiction at time of quote ─────────────────────────────────
    @Column(name = "billing_country", length = 8)
    private String billingCountry;

    @Column(name = "billing_state", length = 16)
    private String billingState;

    @Column(name = "billing_postal_code", length = 20)
    private String billingPostalCode;

    @Column(name = "customer_type", length = 20)
    private String customerType;

    @Column(name = "calculation_version", nullable = false)
    private Integer calculationVersion;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;
}
