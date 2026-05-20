package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One line of an {@link Invoice}.
 */
@Entity
@Table(name = "invoice_lines", indexes = {
    @Index(name = "idx_invoice_lines_invoice", columnList = "invoice_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder(toBuilder = true)
public class InvoiceLine {

    public enum LineType { CHARGE, ADDON, GUEST_FEE, SURGE, DISCOUNT, LOYALTY, PLATFORM_FEE, TAX }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 30)
    private LineType lineType;

    @Column(length = 200)
    private String description;

    @Column(name = "quantity", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "unit_amount", precision = 14, scale = 4)
    private BigDecimal unitAmount;

    @Column(name = "tax_rate_bps")
    private Integer taxRateBps;

    @Column(name = "tax_type", length = 40)
    private String taxType;

    @Column(name = "amount", precision = 14, scale = 4)
    private BigDecimal amount;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;
}
