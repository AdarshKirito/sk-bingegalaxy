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
    /**
     * Customer display name/email, enriched for ADMIN views from the customer's
     * most recent booking snapshot at this binge (null when they never booked).
     * Operators identify people, not ids.
     */
    private String customerName;
    private String customerEmail;
    private Long bingeId;
    private LocalDateTime freezeUntil;
    /**
     * Server-computed seconds until {@link #freezeUntil}. Clients MUST drive
     * countdowns from this (deadline = fetchTime + secondsRemaining), never by
     * parsing the zone-less UTC timestamp as browser-local time.
     */
    private long secondsRemaining;
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
