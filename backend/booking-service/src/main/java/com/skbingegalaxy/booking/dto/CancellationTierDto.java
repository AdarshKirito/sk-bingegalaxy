package com.skbingegalaxy.booking.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationTierDto {
    private Long id;
    private Long bingeId;
    private int hoursBeforeStart;
    private int refundPercentage;
    private String label;
}
