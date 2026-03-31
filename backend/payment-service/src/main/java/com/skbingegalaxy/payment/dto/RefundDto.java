package com.skbingegalaxy.payment.dto;

import com.skbingegalaxy.common.enums.PaymentStatus;
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
    private String failureReason;
    private String initiatedBy;
    private LocalDateTime refundedAt;
    private LocalDateTime createdAt;
}
