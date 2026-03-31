package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "add_ons")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AddOn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 50)
    private String category; // DECORATION, BEVERAGE, PHOTOGRAPHY, EFFECT, FOOD, EXPERIENCE

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "add_on_images", joinColumns = @JoinColumn(name = "add_on_id"))
    @Column(name = "image_url", length = 1000)
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
