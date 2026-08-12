package com.skbingegalaxy.distribution.inbox;

import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Decides whether an inbound provider message may be applied, or has been overtaken.
 *
 * <p><b>The problem this exists for.</b> V85's unique index makes a repeated CREATE
 * harmless — the same message delivered twice is not processed twice. It says nothing
 * about <em>order</em>. Channel delivery is at-least-once and unordered, so a CANCEL can
 * arrive before the MODIFY it supersedes. Applying them in receipt order would apply the
 * cancel, then apply the older modify on top, and <b>resurrect a cancelled booking</b> —
 * a traveller shows up to a slot the venue believes is free, or the venue holds a slot
 * for someone who cancelled.
 *
 * <p>Deduplication and ordering are therefore different problems with different fixes,
 * and having solved the first does not touch the second.
 *
 * <p><b>Why a superseded message is kept, not dropped.</b> A dropped message is
 * invisible; a {@code SUPERSEDED} row is an explanation. When a venue asks why a
 * modification never took effect, the answer has to be a row someone can look at.
 */
public final class MessageOrderingPolicy {

    private MessageOrderingPolicy() {}

    /** What the decision was based on, and therefore how much it can be trusted. */
    public record Decision(boolean apply, ReservationInboxEntry.OrderingBasis basis, String reason) {

        static Decision apply(ReservationInboxEntry.OrderingBasis basis) {
            return new Decision(true, basis, null);
        }

        static Decision supersede(ReservationInboxEntry.OrderingBasis basis, String reason) {
            return new Decision(false, basis, reason);
        }
    }

    /**
     * Evidence that this reservation has already been cancelled.
     *
     * @param exists   a non-superseded CANCEL for this reservation has been received —
     *                 whatever became of it afterwards
     * @param sequence that cancellation's provider sequence, or null when the provider
     *                 supplies none
     */
    public record CancelTombstone(boolean exists, Long sequence) {
        public static CancelTombstone none() {
            return new CancelTombstone(false, null);
        }
    }

    /**
     * As {@link #decide(Long, Long, LocalDateTime, LocalDateTime)}, but also refusing a
     * message that a cancellation has already overtaken.
     *
     * <p><b>The hole the applied high-water mark left.</b> A CANCEL that arrives before
     * its CREATE is passed to booking-service, which correctly refuses it — there is no
     * such reservation yet — so the row ends REJECTED rather than APPLIED. The high-water
     * mark counts only APPLIED messages, so it stayed empty, and when the CREATE arrived
     * it looked like the very first message for a reservation nobody had cancelled. The
     * booking was created, and the cancellation had been silently undone: the venue holds
     * a slot for a traveller who cancelled and will not arrive, and every row involved
     * reads as healthy.
     *
     * <p><b>A cancellation is therefore a tombstone, not just a message.</b> Once one has
     * been received, a CREATE or MODIFY is applied only if the provider's own ordering
     * puts it strictly after that cancellation. Where either side lacks a sequence there
     * is no shared ordering to appeal to, and the message is set aside — the asymmetry is
     * deliberate: wrongly setting aside a message leaves a SUPERSEDED row an operator can
     * see and requeue, while wrongly applying one resurrects a dead booking that nobody
     * is looking for.
     */
    public static Decision decide(Long incomingSequence,
                                  Long lastAppliedSequence,
                                  LocalDateTime incomingTimestamp,
                                  LocalDateTime lastAppliedTimestamp,
                                  CancelTombstone cancelled) {

        if (cancelled != null && cancelled.exists()) {
            boolean bothSequenced = incomingSequence != null && cancelled.sequence() != null;
            ReservationInboxEntry.OrderingBasis basis = bothSequenced
                ? ReservationInboxEntry.OrderingBasis.PROVIDER_SEQUENCE
                : ReservationInboxEntry.OrderingBasis.RECEIPT_ORDER;

            if (!bothSequenced) {
                return Decision.supersede(basis,
                    "a cancellation for this reservation has already been received, and "
                  + "neither message carries a sequence to order them by");
            }
            if (incomingSequence <= cancelled.sequence()) {
                return Decision.supersede(basis,
                    "sequence " + incomingSequence + " does not exceed the cancellation at "
                  + cancelled.sequence());
            }
            // Strictly later than the cancellation: a genuine re-booking of the same
            // reference, which the provider has ordered for us. Falls through to the
            // ordinary rules below.
        }

        return decide(incomingSequence, lastAppliedSequence, incomingTimestamp, lastAppliedTimestamp);
    }

    /**
     * @param incomingSequence provider-supplied ordering value, or null when the provider
     *                         supplies none
     * @param lastAppliedSequence highest sequence already applied for this reservation
     * @param incomingTimestamp provider-supplied message time, used only when there is no
     *                          sequence
     * @param lastAppliedTimestamp time of the last applied message
     */
    public static Decision decide(Long incomingSequence,
                                  Long lastAppliedSequence,
                                  LocalDateTime incomingTimestamp,
                                  LocalDateTime lastAppliedTimestamp) {

        // Nothing applied yet: the first message for a reservation always wins, whatever
        // it is. A cancel arriving before any create is still worth applying — it is the
        // provider telling us a reservation we never saw is already dead.
        if (lastAppliedSequence == null && lastAppliedTimestamp == null) {
            return Decision.apply(basisOf(incomingSequence, incomingTimestamp));
        }

        if (incomingSequence != null && lastAppliedSequence != null) {
            // STRICTLY greater. Equal means the same message again, which the unique
            // index should have caught; treating it as applicable would let a retry
            // overwrite a later state if the two ever raced.
            return incomingSequence > lastAppliedSequence
                ? Decision.apply(ReservationInboxEntry.OrderingBasis.PROVIDER_SEQUENCE)
                : Decision.supersede(ReservationInboxEntry.OrderingBasis.PROVIDER_SEQUENCE,
                    "sequence " + incomingSequence + " does not exceed applied "
                    + lastAppliedSequence);
        }

        // One side has a sequence and the other does not. Mixing the two orderings would
        // compare incomparable values, so fall back to the basis BOTH sides share.
        if (incomingTimestamp != null && lastAppliedTimestamp != null) {
            return incomingTimestamp.isAfter(lastAppliedTimestamp)
                ? Decision.apply(ReservationInboxEntry.OrderingBasis.PROVIDER_TIMESTAMP)
                : Decision.supersede(ReservationInboxEntry.OrderingBasis.PROVIDER_TIMESTAMP,
                    "message time " + incomingTimestamp + " is not after applied "
                    + lastAppliedTimestamp);
        }

        // No shared ordering signal at all. Apply, and record that the ordering was
        // RECEIPT_ORDER — i.e. luck. Refusing instead would strand every message from a
        // provider that supplies neither, but a reconciliation run must be able to tell
        // "the provider ordered this" from "we hoped".
        return Decision.apply(ReservationInboxEntry.OrderingBasis.RECEIPT_ORDER);
    }

    private static ReservationInboxEntry.OrderingBasis basisOf(Long sequence, LocalDateTime timestamp) {
        if (sequence != null) return ReservationInboxEntry.OrderingBasis.PROVIDER_SEQUENCE;
        if (timestamp != null) return ReservationInboxEntry.OrderingBasis.PROVIDER_TIMESTAMP;
        return ReservationInboxEntry.OrderingBasis.RECEIPT_ORDER;
    }

    /** Convenience for the common case where only sequences are in play. */
    public static Decision decide(Long incomingSequence, Optional<Long> lastApplied) {
        return decide(incomingSequence, lastApplied.orElse(null), null, null);
    }
}
