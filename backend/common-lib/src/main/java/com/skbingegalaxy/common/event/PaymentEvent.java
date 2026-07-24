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
    /**
     * Binge the payment row belongs to (SEC-011). Consumers verify this
     * against the booking's own binge before mutating booking state, so a
     * payment stamped with the wrong tenant can never move another tenant's
     * booking. Nullable for events produced by older service versions —
     * consumers must treat null as "unverifiable" and log, not reject.
     */
    private Long bingeId;
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
