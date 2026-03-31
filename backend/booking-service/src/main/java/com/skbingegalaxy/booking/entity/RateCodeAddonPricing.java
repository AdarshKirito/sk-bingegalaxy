package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "rate_code_addon_pricing",
    uniqueConstraints = @UniqueConstraint(columnNames = {"rate_code_id", "add_on_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RateCodeAddonPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rate_code_id", nullable = false)
    private RateCode rateCode;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "add_on_id", nullable = false)
    private AddOn addOn;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}
