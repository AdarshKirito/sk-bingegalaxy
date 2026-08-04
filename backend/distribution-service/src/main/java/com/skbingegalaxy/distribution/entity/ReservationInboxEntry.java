package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A raw inbound message from a destination, persisted <b>before</b> any attempt to
 * create a canonical booking.
 *
 * <p>Persisting first is what turns a rejected reservation into a visible, explainable
 * row instead of a lost booking. It is the difference between an operator seeing
 * <em>"rejected: the 18:00 slot was taken 40 seconds earlier"</em> and a venue asking
 * why a channel reservation never arrived.
 */
@Entity
@Table(name = "reservation_inbox")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ReservationInboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "destination_code", nullable = false, length = 40)
    private String destinationCode;

    @Column(name = "external_ref", nullable = false, length = 128)
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private MessageType messageType;

    /**
     * Provider-supplied ordering. Apply a message only when this exceeds the last
     * applied value; otherwise mark it {@link Status#SUPERSEDED} and keep the row.
     *
     * <p>Creation idempotency alone is not enough: with at-least-once delivery a
     * <em>cancel can arrive before the modify it supersedes</em>, and applying them in
     * receipt order would resurrect a cancelled booking.
     */
    @Column(name = "external_sequence")
    private Long externalSequence;

    /**
     * Which basis actually ordered this message. Recorded because a reconciliation run
     * must be able to distinguish "ordered by the provider" from "ordered by luck" —
     * not every provider supplies a sequence.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ordering_basis", nullable = false, length = 20)
    @Builder.Default
    private OrderingBasis orderingBasis = OrderingBasis.RECEIPT_ORDER;

    /** The provider payload verbatim. Never reinterpreted — the audit trail depends on it. */
    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "received_at", updatable = false, nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.RECEIVED;

    /**
     * Set once the canonical booking exists in booking-service. This context stores the
     * reference and nothing else — no booking detail, no second booking truth.
     */
    @Column(name = "booking_ref", length = 32)
    private String bookingRef;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    public enum MessageType { CREATE, MODIFY, CANCEL, ACKNOWLEDGE }

    public enum Status {
        RECEIVED,
        APPLIED,
        /** Legitimately refused — e.g. the slot was taken. The channel is told. */
        REJECTED,
        /** Arrived out of order and a newer message already won. */
        SUPERSEDED,
        /** Processing errored; retryable from the recovery console. */
        FAILED
    }

    public enum OrderingBasis { PROVIDER_SEQUENCE, PROVIDER_TIMESTAMP, RECEIPT_ORDER }
}
