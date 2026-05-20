package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A tax rule applied at booking time. Resolution:
 *   1. binge_id == current binge  (priority asc)
 *   2. binge_id IS NULL (platform-wide default)
 * All matching active rules are summed.
 */
@Entity
@Table(name = "tax_rules", indexes = {
    @Index(name = "idx_tax_rules_binge", columnList = "binge_id"),
    @Index(name = "idx_tax_rules_active", columnList = "active"),
    @Index(name = "idx_tax_rules_priority", columnList = "priority")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TaxRule {

    public enum AppliesTo { TOTAL, BASE, ADDONS, GUEST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = platform default; otherwise scoped to a binge. */
    @Column(name = "binge_id")
    private Long bingeId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    /** Tax rate in basis points: 1800 = 18.00%. */
    @Column(name = "rate_bps", nullable = false)
    private Integer rateBps;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", nullable = false, length = 20)
    @Builder.Default
    private AppliesTo appliesTo = AppliesTo.TOTAL;

    /** When true the price already includes this tax (display-only breakdown). */
    @Column(nullable = false)
    @Builder.Default
    private boolean inclusive = false;

    @Column(name = "country_code", length = 8)
    private String countryCode;

    @Column(name = "region_code", length = 16)
    private String regionCode;

    @Column(name = "state_code", length = 16)
    private String stateCode;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    /** Optional product/service category, e.g. BOOKING, ADDON, GUEST_FEE, ALL. */
    @Column(name = "product_type", length = 40)
    private String productType;

    /** B2C, B2B, or ALL. */
    @Column(name = "customer_type", length = 20)
    private String customerType;

    /** Tax type label used on invoices: GST, VAT, SALES_TAX, SERVICE_TAX, GENERIC. */
    @Column(name = "tax_type", length = 40, nullable = false)
    @Builder.Default
    private String taxType = "GENERIC";

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "rule_version", nullable = false)
    @Builder.Default
    private Integer ruleVersion = 1;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
