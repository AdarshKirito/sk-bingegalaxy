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

    private Long bingeId;

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

    /**
     * Daily inventory cap for this add-on (e.g. "only 5 cakes per day").
     * NULL means unlimited. Enforced at booking creation/update by counting
     * already-booked instances for the same booking date.
     */
    @Column(name = "stock_per_day")
    private Integer stockPerDay;

    /**
     * Minimum advance-notice required for this add-on, in minutes
     * (e.g. decoration setup needs 6 hours = 360). Enforced as
     * {@code (bookingStart - now) >= advanceNoticeMinutes}.
     * NULL means no advance-notice requirement.
     */
    @Column(name = "advance_notice_minutes")
    private Integer advanceNoticeMinutes;
}
