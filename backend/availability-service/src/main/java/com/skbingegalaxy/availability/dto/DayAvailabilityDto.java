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
    /**
     * True when the venue is CLOSED this weekday under its per-day operating hours
     * (distinct from an admin-blocked date). Drives the "Closed" state in the customer
     * date picker so a closed day is never offered for booking.
     */
    private boolean closed;
    private List<SlotDto> availableSlots;
    private List<SlotDto> blockedSlots;
}
