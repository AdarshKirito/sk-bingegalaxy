package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "event_types")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class EventType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal pricePerGuest = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private int minHours = 1;

    @Column(nullable = false)
    @Builder.Default
    private int maxHours = 8;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_type_images", joinColumns = @JoinColumn(name = "event_type_id"))
    @Column(name = "image_url", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
