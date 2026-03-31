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
    private String name;
    private String description;
    private BigDecimal basePrice;
    private BigDecimal hourlyRate;
    private BigDecimal pricePerGuest;
    private int minHours;
    private int maxHours;
    private List<String> imageUrls;
    private boolean active;
}
