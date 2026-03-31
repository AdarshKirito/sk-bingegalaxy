package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rate_codes")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RateCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @OneToMany(mappedBy = "rateCode", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RateCodeEventPricing> eventPricings = new ArrayList<>();

    @OneToMany(mappedBy = "rateCode", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RateCodeAddonPricing> addonPricings = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
