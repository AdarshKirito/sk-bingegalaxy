package com.skbingegalaxy.availability.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DayAvailabilityDto {
    private LocalDate date;
    private boolean fullyBlocked;
    private List<SlotDto> availableSlots;
    private List<SlotDto> blockedSlots;
}
