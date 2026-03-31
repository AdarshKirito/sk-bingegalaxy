package com.skbingegalaxy.common.event;

import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentEvent implements Serializable {
    private String bookingRef;
    private String transactionId;
    private BigDecimal amount;
    private String currency;
    private String paymentMethod;
    private String status;
    private String customerEmail;
    private String customerPhone;
    private String customerName;
    private LocalDateTime paidAt;
    // Populated only for refund events
    private String refundId;
    private java.math.BigDecimal refundAmount;
    private String refundReason;
}
