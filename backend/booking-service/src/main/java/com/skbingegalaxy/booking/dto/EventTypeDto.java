package com.skbingegalaxy.booking.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventTypeDto {
    private Long id;
    private Long bingeId;
    private String name;
    private String description;
    private BigDecimal basePrice;
    private BigDecimal hourlyRate;
    private BigDecimal pricePerGuest;
    private int minHours;
    private int maxHours;
    /** V81 prep time before a booking, in minutes. NULL = inherit the venue default. */
    private Integer setupMinutes;
    /** V81 turnover time after a booking, in minutes. NULL = inherit the venue default. */
    private Integer cleanupMinutes;
    /** V81 resolved setup buffer actually in force (override, else venue default). Read-only. */
    private Integer effectiveSetupMinutes;
    /** V81 resolved cleanup buffer actually in force (override, else venue default). Read-only. */
    private Integer effectiveCleanupMinutes;
    /**
     * V84 (B5): the durations, in minutes, this event type may be booked for.
     * Empty = no allow-list, so any 30-minute multiple within min/max hours is valid.
     */
    private java.util.List<Integer> permittedDurations;
    /** Per-event-type minimum guest count (null = no lower bound). */
    private Integer minGuests;
    /** Per-event-type maximum guest count (null = no upper bound). */
    private Integer maxGuests;
    /** FK to event_categories. Null = uncategorized. */
    private Long categoryId;
    /** Snapshotted category name for the wizard chip filter (null when uncategorized). */
    private String categoryName;
    private List<String> imageUrls;
    private boolean active;
}
