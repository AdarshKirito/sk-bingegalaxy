package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventTypeSaveRequest {

    @NotBlank(message = "Event type name is required")
    @Size(max = 100, message = "Event type name must be under 100 characters")
    private String name;

    @Size(max = 500, message = "Description must be under 500 characters")
    private String description;

    @NotNull(message = "Base price is required")
    @DecimalMin(value = "0.0", message = "Base price cannot be negative")
    private BigDecimal basePrice;

    @NotNull(message = "Hourly rate is required")
    @DecimalMin(value = "0.0", message = "Hourly rate cannot be negative")
    private BigDecimal hourlyRate;

    @DecimalMin(value = "0.0", message = "Price per guest cannot be negative")
    private BigDecimal pricePerGuest = BigDecimal.ZERO;

    @Min(value = 1, message = "Minimum hours must be at least 1")
    private int minHours = 1;

    @Min(value = 1, message = "Maximum hours must be at least 1")
    @Max(value = 24, message = "Maximum hours cannot exceed 24")
    private int maxHours = 8;

    /**
     * V81 prep time reserved BEFORE each booking of this event type, in minutes.
     * NULL inherits the venue default. Widens the occupancy window, so raising it
     * can make already-tight consecutive slots unbookable — deliberately so.
     */
    @Min(value = 0, message = "Setup minutes cannot be negative")
    @Max(value = 240, message = "Setup minutes cannot exceed 240 (4 hours)")
    private Integer setupMinutes;

    /**
     * V81 turnover time reserved AFTER each booking of this event type, in minutes.
     * NULL inherits the venue default.
     */
    @Min(value = 0, message = "Cleanup minutes cannot be negative")
    @Max(value = 240, message = "Cleanup minutes cannot exceed 240 (4 hours)")
    private Integer cleanupMinutes;

    /**
     * V84 (B5): up to 4 durations in minutes that customers and sales channels may
     * pick, e.g. {@code [120, 180, 240]}. Null or empty restores free choice across
     * the event type's min/max hour range. Validated by {@code BookingWindowPolicy}.
     */
    @Size(max = 4, message = "At most 4 durations can be offered")
    private List<Integer> permittedDurations;

    /** Optional per-event minimum guest count. NULL = no constraint. */
    @Min(value = 1, message = "Minimum guests must be at least 1")
    private Integer minGuests;

    /** Optional per-event maximum guest count. NULL = no constraint. */
    @Min(value = 1, message = "Maximum guests must be at least 1")
    private Integer maxGuests;

    /** Optional grouping category. NULL = uncategorized. */
    private Long categoryId;

    private List<String> imageUrls;
}
