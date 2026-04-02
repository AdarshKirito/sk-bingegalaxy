package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customer_pricing_profiles")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CustomerPricingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    private Long bingeId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "rate_code_id")
    private RateCode rateCode;

    @OneToMany(mappedBy = "customerPricingProfile", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CustomerEventPricing> eventPricings = new ArrayList<>();

    @OneToMany(mappedBy = "customerPricingProfile", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CustomerAddonPricing> addonPricings = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
