package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * GST/VAT compliant invoice header. The line items live in
 * {@link InvoiceLine}; financial amounts mirror the matching
 * {@link BookingPriceSnapshot} so the invoice is reproducible for life.
 */
@Entity
@Table(name = "invoices", indexes = {
    @Index(name = "idx_invoices_booking_ref", columnList = "booking_ref"),
    @Index(name = "idx_invoices_customer", columnList = "customer_id"),
    @Index(name = "idx_invoices_number", columnList = "invoice_number", unique = true),
    @Index(name = "idx_invoices_status", columnList = "status")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder(toBuilder = true)
public class Invoice {

    public enum Status { DRAFT, ISSUED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 64)
    private String invoiceNumber;

    @Column(name = "booking_ref", nullable = false, length = 64)
    private String bookingRef;

    @Column(name = "binge_id")
    private Long bingeId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "snapshot_id")
    private Long snapshotId;

    @Column(name = "billing_address_id")
    private Long billingAddressId;

    @Column(name = "currency_code", nullable = false, length = 8)
    private String currencyCode;

    @Column(name = "subtotal", precision = 14, scale = 4)
    private BigDecimal subtotal;

    @Column(name = "tax_total", precision = 14, scale = 4)
    private BigDecimal taxTotal;

    @Column(name = "discount_total", precision = 14, scale = 4)
    private BigDecimal discountTotal;

    @Column(name = "grand_total", precision = 14, scale = 4)
    private BigDecimal grandTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.DRAFT;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "tax_breakdown_json", columnDefinition = "TEXT")
    private String taxBreakdownJson;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
