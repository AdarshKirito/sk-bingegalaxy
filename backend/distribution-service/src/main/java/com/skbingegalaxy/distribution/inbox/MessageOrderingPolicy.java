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
