package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Credit note issued when a booking is refunded or cancelled. Always
 * references the original {@link Invoice} and reverses some or all of its
 * amounts via paired {@link LedgerEntry} rows.
 */
@Entity
@Table(name = "credit_notes", indexes = {
    @Index(name = "idx_cn_invoice", columnList = "invoice_id"),
    @Index(name = "idx_cn_booking_ref", columnList = "booking_ref"),
    @Index(name = "idx_cn_number", columnList = "credit_note_number", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder(toBuilder = true)
public class CreditNote {

    public enum Reason { CUSTOMER_CANCELLATION, ADMIN_CANCELLATION, BINGE_CANCELLATION, ADJUSTMENT, OTHER }
    public enum Status { ISSUED, VOIDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credit_note_number", nullable = false, unique = true, length = 64)
    private String creditNoteNumber;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "booking_ref", length = 64)
    private String bookingRef;

    @Column(name = "currency_code", nullable = false, length = 8)
    private String currencyCode;

    @Column(name = "amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal amount;

    @Column(name = "tax_amount", precision = 14, scale = 4)
    private BigDecimal taxAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 40, nullable = false)
    @Builder.Default
    private Reason reason = Reason.CUSTOMER_CANCELLATION;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.ISSUED;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;
}
