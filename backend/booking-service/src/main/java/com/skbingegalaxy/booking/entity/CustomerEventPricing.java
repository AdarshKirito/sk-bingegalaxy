package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "customer_event_pricing",
    uniqueConstraints = @UniqueConstraint(columnNames = {"customer_pricing_profile_id", "event_type_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CustomerEventPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_pricing_profile_id", nullable = false)
    private CustomerPricingProfile customerPricingProfile;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "event_type_id", nullable = false)
    private EventType eventType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal pricePerGuest = BigDecimal.ZERO;
}
