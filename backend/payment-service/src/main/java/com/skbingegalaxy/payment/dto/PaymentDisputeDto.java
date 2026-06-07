package com.skbingegalaxy.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only view of a payment dispute for admin ops dashboards.
 * Includes the respond-by deadline so ops can triage by urgency.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentDisputeDto {

    private Long id;
    private String gatewayDisputeId;
    private Long paymentId;
    private String transactionId;
    private String bookingRef;
    private Long bingeId;
    private BigDecimal amount;
    private String currency;

    /** OPEN | UNDER_REVIEW | WON | LOST | ACCEPTED */
    private String status;

    private String reasonCode;
    private String reasonDescription;

    /** Deadline for merchant evidence submission — alert ops when this is < 24h away. */
    private LocalDateTime respondBy;

    private LocalDateTime gatewayCreatedAt;
    private String opsNotes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Minutes remaining until respondBy deadline (negative = overdue). */
    private Long minutesUntilDeadline;
}
