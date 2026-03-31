package com.skbingegalaxy.availability.dto;

import lombok.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedDateDto {
    private Long id;
    private LocalDate date;
    private String reason;
    private Long blockedBy;
}
