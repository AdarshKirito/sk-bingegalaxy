package com.skbingegalaxy.distribution.dto;

import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * An inbound message as the recovery console sees it.
 *
 * <p><b>The raw payload is deliberately not included.</b> It is provider data of unknown
 * shape that may carry traveller PII, and a recovery screen needs to know WHAT happened,
 * not to render an arbitrary JSON blob into a browser. The row stays verbatim in the
 * database for audit; exposing it is a separate decision with its own access rules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxEntryDto {
    private Long id;
    private Long connectionId;
    private String destinationCode;
    private String destinationName;
    private String externalRef;
    private ReservationInboxEntry.MessageType messageType;
    private ReservationInboxEntry.Status status;

    /**
     * Whether the provider ordered this message or receipt order did. Surfaced because
     * RECEIPT_ORDER means the sequence was luck, and an operator reconciling a dispute
     * needs to know which of those they are looking at.
     */
    private ReservationInboxEntry.OrderingBasis orderingBasis;

    private Long externalSequence;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;

    /** Set once booking-service holds the canonical reservation. */
    private String bookingRef;

    /** Why it was rejected or superseded. The whole point of keeping the row. */
    private String rejectReason;
}
