package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomBlockDto {
    private Long id;
    private Long roomId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String reason;
    private Long createdBy;
    private LocalDateTime createdAt;
}
