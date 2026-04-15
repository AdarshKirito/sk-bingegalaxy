package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyTransactionDto {
    private Long id;
    private String bookingRef;
    private String type;
    private long points;
    private String description;
    private LocalDateTime createdAt;
}
