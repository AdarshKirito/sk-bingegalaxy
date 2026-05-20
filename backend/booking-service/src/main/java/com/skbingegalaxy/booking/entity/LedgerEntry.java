package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only double-entry ledger row. Every monetary movement (charge,
 * tax-collected, platform fee, venue payable, loyalty redemption, refund,
 * tax reversal, FX adjustment, cancellation fee) is recorded here. The
 * matching DB-side trigger {@code fn_block_ledger_mutation()} forbids
 * UPDATE / DELETE — adjustments are made by inserting a new reversal row.
 *
 * Idempotency is enforced via the unique {@code entry_uuid}.
 */
@Entity
@Table(name = "ledger_entries", indexes = {
    @Index(name = "idx_ledger_booking_ref", columnList = "booking_ref"),
    @Index(name = "idx_ledger_invoice", columnList = "invoice_id"),
    @Index(name = "idx_ledger_entry_type", columnList = "entry_type"),
    @Index(name = "idx_ledger_uuid", columnList = "entry_uuid", unique = true),
    @Index(name = "idx_ledger_occurred_at", columnList = "occurred_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
public class LedgerEntry {

    public enum EntryType {
        CHARGE,
        TAX_COLLECTED,
        PLATFORM_FEE,
        VENUE_PAYABLE,
        LOYALTY_REDEMPTION,
        REFUND,
        TAX_REVERSAL,
        CANCELLATION_FEE,
        FX_ADJUSTMENT
    }

    public enum Direction { DEBIT, CREDIT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_uuid", nullable = false, unique = true, length = 64)
    private String entryUuid;

    @Column(name = "booking_ref", length = 64)
    private String bookingRef;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "credit_note_id")
    private Long creditNoteId;

    @Column(name = "binge_id")
    private Long bingeId;

    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 40)
    private EntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 8)
    private String currencyCode;

    @Column(name = "fx_rate_to_base", precision = 20, scale = 10)
    private BigDecimal fxRateToBase;

    @Column(name = "amount_in_base", precision = 14, scale = 4)
    private BigDecimal amountInBase;

    /** Self-FK (Long id) to the original entry being reversed (for REFUND / TAX_REVERSAL). */
    @Column(name = "reversal_of")
    private Long reversalOf;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "occurred_at", updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "recorded_by", length = 120)
    private String recordedBy;
}
