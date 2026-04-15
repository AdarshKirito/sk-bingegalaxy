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

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
