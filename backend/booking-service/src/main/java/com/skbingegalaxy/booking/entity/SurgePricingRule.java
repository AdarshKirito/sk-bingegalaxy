package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "surge_pricing_rules", indexes = {
    @Index(name = "idx_surge_binge", columnList = "bingeId")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SurgePricingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bingeId;

    @Column(nullable = false, length = 100)
    private String name;

    /** Day of week: 1=Monday ... 7=Sunday (ISO-8601). Null means applies to all days. */
    @Column
    private Integer dayOfWeek;

    /** Start minute of day (inclusive). E.g. 1080 = 18:00 */
    @Column(nullable = false)
    private int startMinute;

    /** End minute of day (exclusive). E.g. 1380 = 23:00 */
    @Column(nullable = false)
    private int endMinute;

    /** Surge multiplier applied to base pricing. E.g. 1.5 = 50% surcharge. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal multiplier;

    /** Human-readable label shown to customers. E.g. "Weekend Peak" */
    @Column(length = 100)
    private String label;

    /** Seasonal/event window (inclusive). Null = no date restriction. */
    @Column(name = "date_from")
    private java.time.LocalDate dateFrom;

    @Column(name = "date_to")
    private java.time.LocalDate dateTo;

    /**
     * Last-minute premium: rule applies only when the booking starts within
     * this many hours of "now" (venue clock). Null = no lead-time ceiling.
     */
    @Column(name = "lead_time_max_hours")
    private Integer leadTimeMaxHours;

    /**
     * Early-bird window: rule applies only when the booking is made at least
     * this many hours ahead. Pair with a multiplier &lt; 1 for a discount.
     */
    @Column(name = "lead_time_min_hours")
    private Integer leadTimeMinHours;

    /**
     * Demand trigger: rule applies only once the booking date is at least this
     * % occupied (booked minutes ÷ operating-window capacity). Null = always.
     */
    @Column(name = "occupancy_threshold_pct")
    private Integer occupancyThresholdPct;

    /** Winner among overlapping rules: lowest priority number wins; ties → highest multiplier. */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
