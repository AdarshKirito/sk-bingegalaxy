package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBingeFreezeDto {
    private Long id;
    private Long customerId;
    private Long bingeId;
    private LocalDateTime freezeUntil;
    private String reason;
    /** ACTIVE | LIFTED | EXPIRED */
    private String status;
    /** CUSTOMER_CANCELLATIONS | PAYMENT_TIMEOUTS | MANUAL */
    private String triggerType;
    private Long triggeredByUserId;
    private Long liftedByUserId;
    private LocalDateTime liftedAt;
    private String liftedReason;
    private LocalDateTime createdAt;
}
