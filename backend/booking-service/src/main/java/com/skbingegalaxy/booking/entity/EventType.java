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

    private Long bingeId;

    @Column(nullable = false, length = 100)
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

    // ── V81: turnover buffers (NULL = inherit the binge default) ─────────
    /**
     * Prep time in minutes reserved BEFORE a booking of this event type.
     * NULL inherits {@code Binge.defaultSetupMinutes} — the same "narrower
     * scope NULL means inherit" idiom as {@code Binge.openTime}.
     * Range 0..240 when set, enforced by a DB CHECK.
     */
    @Column(name = "setup_minutes")
    private Integer setupMinutes;

    /**
     * Turnover time in minutes reserved AFTER a booking of this event type
     * (reset, clean, re-decorate). NULL inherits {@code Binge.defaultCleanupMinutes}.
     */
    @Column(name = "cleanup_minutes")
    private Integer cleanupMinutes;

    /**
     * V84 (decision B5): up to 4 comma-separated durations in minutes that may be
     * booked, e.g. {@code "120,180,240"}. NULL means no allow-list — any 30-minute
     * multiple within {@link #minHours}..{@link #maxHours} is valid, the pre-V84
     * behaviour. Read it through {@code BookingWindowPolicy}, never parse it inline.
     */
    @Column(name = "permitted_durations_csv", length = 64)
    private String permittedDurationsCsv;

    /** Per-event-type minimum guest count. NULL = no lower bound. */
    @Column(name = "min_guests")
    private Integer minGuests;

    /** Per-event-type maximum guest count. NULL = no upper bound. */
    @Column(name = "max_guests")
    private Integer maxGuests;

    /**
     * Optional grouping category for filtering in the customer-facing
     * wizard. NULL = uncategorized (surfaces only under the "All" filter).
     * FK to {@code event_categories.id} — see V55.
     */
    @Column(name = "category_id")
    private Long categoryId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_type_images", joinColumns = @JoinColumn(name = "event_type_id"))
    @Column(name = "image_url", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
