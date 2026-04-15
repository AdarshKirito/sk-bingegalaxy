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
    private int startMinute;
    private int endMinute;
    private String reason;
    private Long blockedBy;
}
