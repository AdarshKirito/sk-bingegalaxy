package com.skbingegalaxy.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@ToString(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentEvent extends EventEnvelope {
    private String bookingRef;
    private String transactionId;
    private BigDecimal amount;
    private String currency;
    private String paymentMethod;
    private String status;
    private String customerEmail;
    private String customerPhone;
    /** E.164 dial prefix without subscriber number (e.g. "+91"). */
    private String customerPhoneCountryCode;
    private String customerName;
    private LocalDateTime paidAt;
    // Populated only for refund events
    private String refundId;
    private java.math.BigDecimal refundAmount;
    private String refundReason;
}
