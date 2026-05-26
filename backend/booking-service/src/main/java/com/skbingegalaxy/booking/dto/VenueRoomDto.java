package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
    // V56
    private BigDecimal priceAddition;
    private String status;
    private Long approvalDecidedBy;
    private LocalDateTime approvalDecidedAt;
    private String approvalRejectionReason;
    private List<String> imageUrls;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
