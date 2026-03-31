package com.skbingegalaxy.booking.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddOnSelection {
    private Long addOnId;
    private int quantity;
}
