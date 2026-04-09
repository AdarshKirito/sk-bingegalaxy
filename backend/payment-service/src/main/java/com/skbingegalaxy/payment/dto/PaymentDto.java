package com.skbingegalaxy.payment.dto;

import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.common.enums.PaymentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentDto {

    private Long id;
    private String bookingRef;
    private Long customerId;
    private String transactionId;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private BigDecimal amount;
    private BigDecimal gatewayFee;
    private BigDecimal tax;
    private PaymentMethod paymentMethod;
    private PaymentStatus status;
    private String currency;
    private String failureReason;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    /** Sum of all completed refunds against this payment. */
    private BigDecimal totalRefunded;
    /** How much can still be refunded (amount - totalRefunded). */
    private BigDecimal remainingRefundable;
    /** Number of completed refunds issued. */
    private Integer refundCount;
    /**
     * Razorpay public key ID — populated for INITIATED payments so the
     * frontend can open the Razorpay checkout modal. Never populated when
     * simulation mode is enabled.
     */
    private String razorpayKeyId;
}
