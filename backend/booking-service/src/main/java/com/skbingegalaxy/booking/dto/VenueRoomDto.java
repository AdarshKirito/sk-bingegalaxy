package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VenueRoomDto {
    private Long id;
    private Long bingeId;
    private String name;
    private String roomType;
    private int capacity;
    private String description;
    private int sortOrder;
    private boolean active;
    /** Number of bookings currently using this room for the queried slot. */
    private Integer currentOccupancy;
    /** Whether the room is available for the queried slot. */
    private Boolean available;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
