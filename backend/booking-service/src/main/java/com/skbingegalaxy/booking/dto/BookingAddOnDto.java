package com.skbingegalaxy.booking.dto;

import lombok.*;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingAddOnDto {
    private Long addOnId;
    private String name;
    private String category;
    private int quantity;
    private BigDecimal price;
}
