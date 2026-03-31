package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "customer_addon_pricing",
    uniqueConstraints = @UniqueConstraint(columnNames = {"customer_pricing_profile_id", "add_on_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CustomerAddonPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_pricing_profile_id", nullable = false)
    private CustomerPricingProfile customerPricingProfile;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "add_on_id", nullable = false)
    private AddOn addOn;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}
