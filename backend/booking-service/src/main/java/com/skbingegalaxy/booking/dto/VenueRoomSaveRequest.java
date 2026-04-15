package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VenueRoomSaveRequest {

    @NotBlank(message = "Room name is required")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Room type is required")
    @Size(max = 30)
    private String roomType;

    @Min(value = 1, message = "Capacity must be at least 1")
    private int capacity;

    @Size(max = 500)
    private String description;

    private int sortOrder;

    private boolean active;
}
