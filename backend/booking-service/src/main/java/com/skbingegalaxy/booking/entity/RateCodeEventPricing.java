package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "rate_code_event_pricing",
    uniqueConstraints = @UniqueConstraint(columnNames = {"rate_code_id", "event_type_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RateCodeEventPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rate_code_id", nullable = false)
    private RateCode rateCode;

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
