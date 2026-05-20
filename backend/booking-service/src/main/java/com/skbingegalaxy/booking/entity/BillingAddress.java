package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Customer billing address. Required for B2B invoices and tax-jurisdiction
 * resolution. Stored separately from a user profile so it can be associated
 * to a booking even when the customer is a guest.
 */
@Entity
@Table(name = "billing_addresses", indexes = {
    @Index(name = "idx_billing_addr_customer", columnList = "customer_id"),
    @Index(name = "idx_billing_addr_country", columnList = "country_code")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder(toBuilder = true)
public class BillingAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "tax_id", length = 60)
    private String taxId;

    /** B2C, B2B. */
    @Column(name = "customer_type", length = 20)
    @Builder.Default
    private String customerType = "B2C";

    @Column(name = "address_line1", length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "state_code", length = 16)
    private String stateCode;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country_code", length = 8)
    private String countryCode;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
