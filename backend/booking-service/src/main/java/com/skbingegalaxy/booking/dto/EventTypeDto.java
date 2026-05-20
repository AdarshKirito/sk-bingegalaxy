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
    /** Per-event-type minimum guest count (null = no lower bound). */
    private Integer minGuests;
    /** Per-event-type maximum guest count (null = no upper bound). */
    private Integer maxGuests;
    private List<String> imageUrls;
    private boolean active;
}
