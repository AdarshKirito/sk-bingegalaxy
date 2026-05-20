package com.skbingegalaxy.payment.dto;

import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.payment.entity.RefundStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RefundDto {

    private Long id;
    private Long paymentId;
    private String bookingRef;
    private BigDecimal amount;
    private String reason;
    private String gatewayRefundId;
    private PaymentStatus status;
    /** Per-attempt refund lifecycle. */
    private RefundStatus refundStatus;
    /** If this is a retry, the original attempt id. */
    private Long retryOfId;
    private int retryCount;
    private String failureReason;
    private String initiatedBy;
    private LocalDateTime refundedAt;
    private LocalDateTime createdAt;
}
