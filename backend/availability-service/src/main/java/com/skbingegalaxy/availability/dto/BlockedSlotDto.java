package com.skbingegalaxy.availability.dto;

import lombok.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedSlotDto {
    private Long id;
    private LocalDate date;
    private int startHour;
    private int endHour;
    private String reason;
    private Long blockedBy;
}
