package com.skbingegalaxy.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AdminApprovalRequestDto {
    private Long id;
    private String actionType;
    private String resourceType;
    private String resourceId;
    private BigDecimal amount;
    private String currency;
    private Long bingeId;
    private String status;
    private String requestedBy;
    private Long requestedById;
    private LocalDateTime requestedAt;
    private String requestReason;
    private String reviewedBy;
    private Long reviewedById;
    private LocalDateTime reviewedAt;
    private String reviewReason;
    private LocalDateTime executedAt;
    private String executedResult;
    private LocalDateTime expiresAt;
    private String payload;
}
