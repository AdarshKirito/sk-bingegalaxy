package com.skbingegalaxy.availability.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SlotDto {
    private int startHour;
    private int endHour;
    private int startMinute;  // minutes from midnight (e.g. 540 = 09:00, 570 = 09:30)
    private int endMinute;    // minutes from midnight
    private String label;     // e.g. "09:00 - 09:30"
    private boolean available;
}
