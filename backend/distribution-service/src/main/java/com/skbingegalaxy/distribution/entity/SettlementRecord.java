package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Money a third party is holding or owes for one booking on one destination.
 *
 * <p><b>This is a receivable, not a payment.</b> A {@code CHANNEL_COLLECTS} reservation
 * must never create a Razorpay or Stripe intent: payment-service remains the authority
 * for SK-controlled money only. Faking channel money through the existing payment flow
 * would corrupt reconciliation, the ledger and refund logic at the same time.
 *
 * <p>All amounts are <b>minor-unit longs</b>, matching the platform money contract.
 */
@Entity
@Table(name = "settlement_records")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SettlementRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_ref", nullable = false, length = 32)
    private String bookingRef;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "destination_code", nullable = false, length = 40)
    private String destinationCode;

    /**
     * Currency the destination actually remits in. Tracked separately from
     * {@link #venuePayoutCurrency} because a destination may settle in one currency
     * while the venue banks in another — routine once the platform is global, and the
     * variance between them is a real reconciliation category rather than an error.
     */
    @Column(name = "settlement_currency", nullable = false, length = 3)
    private String settlementCurrency;

    /**
     * Both currency columns carry a {@code ^[A-Z]{3}$} CHECK. Normalising in the setter
     * turns a lowercase code into a stored uppercase one rather than a raw
     * {@code check constraint "ck_settlement_currency_iso"} violation surfacing to a
     * caller who cannot act on it — the same reason canonicalisation lives in the setter
     * on {@code ChannelReservationRequest}.
     */
    public void setSettlementCurrency(String settlementCurrency) {
        this.settlementCurrency = normaliseCurrency(settlementCurrency);
    }

    public void setVenuePayoutCurrency(String venuePayoutCurrency) {
        this.venuePayoutCurrency = normaliseCurrency(venuePayoutCurrency);
    }

    public static String normaliseCurrency(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The setters alone are not enough: {@code @Builder} assigns fields directly and
     * never calls them, so a builder-constructed record would bypass normalisation
     * entirely. The lifecycle callback is the one place every construction path
     * converges on before the CHECK constraint sees the value.
     */
    @PrePersist
    @PreUpdate
    void normaliseCurrenciesBeforeWrite() {
        this.settlementCurrency = normaliseCurrency(this.settlementCurrency);
        this.venuePayoutCurrency = normaliseCurrency(this.venuePayoutCurrency);
    }

    @Column(name = "gross_minor", nullable = false)
    @Builder.Default
    private long grossMinor = 0;

    @Column(name = "taxes_minor", nullable = false)
    @Builder.Default
    private long taxesMinor = 0;

    @Column(name = "fees_minor", nullable = false)
    @Builder.Default
    private long feesMinor = 0;

    @Column(name = "commission_minor", nullable = false)
    @Builder.Default
    private long commissionMinor = 0;

    @Column(name = "platform_fee_minor", nullable = false)
    @Builder.Default
    private long platformFeeMinor = 0;

    @Column(name = "venue_net_minor", nullable = false)
    @Builder.Default
    private long venueNetMinor = 0;

    @Column(name = "venue_payout_currency", length = 3)
    private String venuePayoutCurrency;

    /** Captured on receipt so a historical payout stays reproducible after rates move. */
    @Column(name = "fx_rate_at_payout", precision = 18, scale = 8)
    private BigDecimal fxRateAtPayout;

    @Enumerated(EnumType.STRING)
    @Column(name = "collected_by", nullable = false, length = 28)
    private CollectedBy collectedBy;

    @Column(name = "deposit_minor", nullable = false)
    @Builder.Default
    private long depositMinor = 0;

    @Column(name = "outstanding_minor", nullable = false)
    @Builder.Default
    private long outstandingMinor = 0;

    /**
     * When the destination is expected to pay. Viator pays the net rate only
     * <em>after</em> the experience completes, so this is frequently well past the
     * booking date — which is exactly why the console must not present channel revenue
     * as money already in the bank.
     */
    @Column(name = "expected_payout_at")
    private LocalDate expectedPayoutAt;

    @Column(name = "actual_payout_at")
    private LocalDate actualPayoutAt;

    @Column(name = "actual_payout_minor")
    private Long actualPayoutMinor;

    /** Refund and cancellation charges can land on different parties for the same booking. */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_responsibility", length = 16)
    private Party refundResponsibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_charge_responsibility", length = 16)
    private Party cancellationChargeResponsibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false, length = 20)
    @Builder.Default
    private SettlementStatus settlementStatus = SettlementStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 20)
    @Builder.Default
    private ReconciliationStatus reconciliationStatus = ReconciliationStatus.UNMATCHED;

    /** Signed difference between expected and actual. Surfaced, never silently absorbed. */
    @Column(name = "variance_minor", nullable = false)
    @Builder.Default
    private long varianceMinor = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum CollectedBy { VENUE, SK_BINGE, CHANNEL, PAY_AT_VENUE, VIRTUAL_CARD, MIXED }

    public enum Party { VENUE, CHANNEL, SK_BINGE }

    public enum SettlementStatus {
        PENDING, EXPECTED, PAID, SHORT_PAID, OVER_PAID, DISPUTED, WRITTEN_OFF
    }

    public enum ReconciliationStatus {
        UNMATCHED, MATCHED, VARIANCE, INVESTIGATING, RESOLVED
    }
}
