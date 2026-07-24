package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;

/** API shape for a {@link com.skbingegalaxy.booking.entity.BingeChangeRequest}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BingeChangeRequestDto {
    private Long id;
    private Long bingeId;
    private String bingeName;
    private String requestType;
    private String currentValue;
    private String requestedValue;
    /** Currency the binge currently charges in. */
    private String currentCurrency;
    /** Currency the binge would switch to if approved. */
    private String requestedCurrency;
    private String reason;
    private Long requestedByAdminId;
    private String status;
    private Long decidedByUserId;
    private LocalDateTime decidedAt;
    private String decisionNote;
    private LocalDateTime createdAt;
}
