package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.inbox.MessageOrderingPolicy;
import com.skbingegalaxy.distribution.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Inbound provider messages (distribution slice 5, design G-C).
 *
 * <p><b>Persist first, decide second.</b> The raw message is written before anything is
 * interpreted. That single ordering is what turns a refused reservation into a visible,
 * explainable row instead of a lost booking: the difference between an operator seeing
 * <em>"rejected: the 18:00 slot was taken 40 seconds earlier"</em> and a venue asking why
 * a channel reservation never arrived.
 *
 * <p><b>This service does not create bookings.</b> Reservations are canonical in
 * booking-service, which already exposes the channel-ingestion seam. The inbox records
 * what arrived, decides whether it is still current, and stores the resulting
 * {@code bookingRef}. A second booking truth here is the failure mode the whole
 * distribution design is built to avoid.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationInboxService {

    private final ReservationInboxRepository inboxRepository;
    private final InboxWriter inboxWriter;
    private final ConnectionRepository connectionRepository;
    private final ConnectionDestinationRepository connectionDestinationRepository;
    private final DestinationRepository destinationRepository;
    private final SettlementService settlementService;

    /** Ceiling on a stored payload; mirrors {@code distribution.inbox.max-payload-bytes}. */
    public static final int MAX_PAYLOAD_BYTES = 262_144;

    /**
     * Record an inbound message and decide whether it may be applied.
     *
     * <p><b>Deliberately not transactional itself.</b> The write and the duplicate
     * recovery each run in their own transaction inside {@link InboxWriter}, because a
     * unique-index violation poisons the Hibernate session it happened on: the recovery
     * lookup used to run on that same poisoned session and blew up with
     * {@code AssertionFailure: null id}, so the path built to make redelivery harmless
     * answered HTTP 500 instead. Wrapping this method in a transaction again would
     * re-create exactly that — the writer's {@code REQUIRES_NEW} would still isolate the
     * insert, but the surrounding transaction would carry the failure onward.
     *
     * <p>The validations below are reads, and their correctness does not depend on
     * sharing a transaction with the write: each is a precondition the provider either
     * satisfies or does not.
     */
    public ReservationInboxEntry receive(Long connectionId,
                                         String destinationCode,
                                         String externalRef,
                                         ReservationInboxEntry.MessageType messageType,
                                         Long externalSequence,
                                         LocalDateTime providerTimestamp,
                                         String payloadJson) {

        Connection connection = connectionRepository.findById(connectionId)
            .orElseThrow(() -> new BusinessException("Unknown connection: " + connectionId));

        // A revoked connection must not keep accepting reservations. Paused is different
        // and deliberately still accepted: pausing stops NEW sales, and a message about a
        // reservation already taken still has to be honoured.
        if (connection.getStatus() == Connection.ConnectionStatus.REVOKED) {
            throw new BusinessException("Connection " + connectionId + " is revoked.");
        }

        Destination destination = destinationRepository.findById(destinationCode)
            .orElseThrow(() -> new BusinessException("Unknown destination: " + destinationCode));

        // Google Things to Do is a feed plus a deep link and never delivers a
        // reservation back. A message claiming otherwise is not a reservation we missed;
        // it is a misconfiguration or a forgery, and accepting it would populate the
        // inbox with an object that does not exist.
        if (!destination.isDeliversReservations()) {
            throw new BusinessException(
                destination.getDisplayName() + " does not deliver reservations.");
        }

        connectionDestinationRepository
            .findByConnectionIdAndDestinationCode(connectionId, destinationCode)
            .orElseThrow(() -> new BusinessException(
                "This connection does not reach " + destinationCode + "."));

        String payload = payloadJson == null ? "" : payloadJson;
        if (payload.length() > MAX_PAYLOAD_BYTES) {
            // The inbox keeps every payload verbatim for audit, so without a bound one
            // hostile or misbehaving caller could fill the disk.
            throw new BusinessException("Payload exceeds " + MAX_PAYLOAD_BYTES + " bytes.");
        }

        MessageOrderingPolicy.Decision decision = MessageOrderingPolicy.decide(
            externalSequence,
            inboxRepository.findHighestAppliedSequence(connectionId, externalRef).orElse(null),
            providerTimestamp,
            null,
            cancelTombstone(connectionId, externalRef, messageType));

        ReservationInboxEntry entry = ReservationInboxEntry.builder()
            .connectionId(connectionId)
            .destinationCode(destinationCode)
            .externalRef(externalRef)
            .messageType(messageType)
            .externalSequence(externalSequence)
            .orderingBasis(decision.basis())
            .payloadJson(payload)
            .status(decision.apply()
                ? ReservationInboxEntry.Status.RECEIVED
                : ReservationInboxEntry.Status.SUPERSEDED)
            .rejectReason(decision.apply() ? null : decision.reason())
            .build();

        // Fast path. A provider redelivering is routine under at-least-once, and letting
        // every redelivery reach the unique index meant Hibernate logged a SQL ERROR for
        // ordinary traffic. This does NOT replace the constraint — it cannot close the
        // time-of-check window, and the catch below is still what makes duplicate
        // rejection true under concurrency.
        Optional<ReservationInboxEntry> alreadyHave =
            inboxWriter.findCollision(connectionId, externalRef, messageType, externalSequence);
        if (alreadyHave.isPresent()) {
            log.debug("Redelivered {} for connection {} ref {} — returning inbox {}",
                messageType, connectionId, externalRef, alreadyHave.get().getId());
            return alreadyHave.get();
        }

        try {
            ReservationInboxEntry saved = inboxWriter.insert(entry);
            if (!decision.apply()) {
                // Kept, not dropped. When a venue asks why a modification never took
                // effect, the answer has to be a row someone can look at.
                log.info("Inbox {} superseded ({}): {}",
                    saved.getId(), decision.basis(), decision.reason());
            }
            return saved;
        } catch (DataIntegrityViolationException e) {
            // uk_inbox_message. Duplicate delivery is EXPECTED under at-least-once, so it
            // is a normal outcome rather than an error. Relying on the constraint instead
            // of a pre-read also removes the time-of-check race a lookup would have.
            //
            // The lookup runs in a NEW transaction (see InboxWriter). Running it on the
            // session the violation happened on is what turned this "normal outcome"
            // into a 500 for every redelivered message.
            log.debug("Duplicate inbound message for connection {} ref {} ({})",
                connectionId, externalRef, messageType);
            return inboxWriter.findExisting(connectionId, externalRef, messageType)
                .orElseThrow(() -> e);
        }
    }

    /**
     * Evidence that this reservation was already cancelled, for the ordering decision.
     *
     * <p><b>Only consulted for messages that could resurrect something.</b> A CANCEL is
     * never blocked by an earlier CANCEL: a repeated cancellation is either a redelivery
     * (which the unique index handles) or a later one, and booking-service's cancel is
     * idempotent either way. Blocking it would turn at-least-once delivery into a source
     * of stuck messages.
     *
     * <p>Two narrow queries rather than loading the reservation's message history: an
     * inbox payload can be a quarter of a megabyte, and this runs on every inbound
     * message.
     */
    private MessageOrderingPolicy.CancelTombstone cancelTombstone(
            Long connectionId, String externalRef, ReservationInboxEntry.MessageType messageType) {

        if (messageType == ReservationInboxEntry.MessageType.CANCEL) {
            return MessageOrderingPolicy.CancelTombstone.none();
        }
        boolean cancelled = inboxRepository
            .existsByConnectionIdAndExternalRefAndMessageTypeAndStatusNot(
                connectionId, externalRef,
                ReservationInboxEntry.MessageType.CANCEL,
                ReservationInboxEntry.Status.SUPERSEDED);
        if (!cancelled) {
            return MessageOrderingPolicy.CancelTombstone.none();
        }
        return new MessageOrderingPolicy.CancelTombstone(true,
            inboxRepository.findHighestSequenceForType(
                connectionId, externalRef,
                ReservationInboxEntry.MessageType.CANCEL,
                ReservationInboxEntry.Status.SUPERSEDED).orElse(null));
    }

    /**
     * Mark a message applied once booking-service has the canonical reservation, and
     * create the receivable it implies.
     *
     * <p>The settlement is created HERE because this is the only point that holds the
     * connection, the destination's commercial terms and the booking reference together.
     * Without it {@code settlement_records} had no writer at all: the settlements
     * service, endpoints and console were all correct and permanently empty.
     *
     * <p>{@code grossMinor} is what the CHANNEL charged the traveller, which is not
     * necessarily what SK Binge would have charged — the destination sets its own retail
     * price. It therefore comes from the provider message, not from our pricing engine.
     */
    @Transactional
    public ReservationInboxEntry markApplied(Long entryId, String bookingRef,
                                             Long bingeId, String currency, long grossMinor) {
        ReservationInboxEntry entry = inboxRepository.findById(entryId)
            .orElseThrow(() -> new BusinessException("Unknown inbox entry: " + entryId));
        entry.setStatus(ReservationInboxEntry.Status.APPLIED);
        entry.setBookingRef(bookingRef);
        entry.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
        ReservationInboxEntry saved = inboxRepository.save(entry);

        // Only a SALE creates a receivable. A cancellation or a confirmation carries no
        // price and must not produce one — the original sale's settlement is already
        // recorded and is not affected by either.
        if (entry.getMessageType() != ReservationInboxEntry.MessageType.CREATE) {
            return saved;
        }

        // A sale with no price is different, and worth saying out loud. A settlement row
        // with a null currency cannot be stored and one with a zero gross would
        // understate what the channel owes — worse than an absent row, because it looks
        // reconciled. Warned rather than swallowed: this is money that will not be
        // chased unless somebody notices.
        if (bookingRef != null && (currency == null || currency.isBlank())) {
            log.warn("Booking {} applied from inbox {} with no channel price — "
                + "no receivable created. Reconcile it by hand.", bookingRef, entryId);
            return saved;
        }

        // Terms come from the connection/destination pairing, never from the message —
        // a provider must not be able to declare its own commission by sending a number.
        if (bookingRef != null) {
            connectionDestinationRepository
                .findByConnectionIdAndDestinationCode(entry.getConnectionId(), entry.getDestinationCode())
                .ifPresent(terms -> settlementService.createForChannelBooking(
                    bingeId, entry.getConnectionId(), bookingRef, terms, currency, grossMinor));
        }

        return saved;
    }

    /**
     * Where a reservation actually ended up, in the reseller's own vocabulary.
     *
     * @param status           OCTO lifecycle state, or a terminal failure
     * @param supplierReference the SK booking reference once one exists, else null
     * @param reason           why, when the answer is a refusal — never null for one
     * @param pending          true while the outcome is still being decided, so a caller
     *                          knows whether polling again can change the answer
     */
    public record ReservationOutcome(String status, String supplierReference,
                                     String reason, boolean pending) {}

    /**
     * The final state of a reservation, for the reseller that submitted it.
     *
     * <p><b>Why this endpoint has to exist.</b> Every write on the OCTO surface is
     * accepted into the inbox and answered {@code PENDING} — the canonical booking is
     * created by a sweep that runs afterwards. That is the right shape, but it left the
     * reseller with no way to ever learn the outcome: no status endpoint, no callback, no
     * result event. A reservation that was refused because the slot had been taken forty
     * seconds earlier looked, from the reseller's side, exactly like one that succeeded.
     * The traveller is told they are booked either way.
     *
     * <p>Derived from the messages rather than stored: the inbox already holds every
     * lifecycle step, and a second status field would be a copy that can disagree with
     * the rows it summarises.
     */
    public Optional<ReservationOutcome> outcomeFor(Long connectionId, String externalRef) {
        List<ReservationInboxEntry> messages =
            inboxRepository.findByConnectionIdAndExternalRefOrderByIdAsc(connectionId, externalRef);
        if (messages.isEmpty()) return Optional.empty();

        ReservationInboxEntry latest = messages.get(messages.size() - 1);
        String bookingRef = messages.stream()
            .map(ReservationInboxEntry::getBookingRef)
            .filter(java.util.Objects::nonNull)
            .reduce((first, second) -> second)
            .orElse(null);

        // Cancellation wins over everything: a cancelled reservation that also has an
        // applied CREATE is cancelled, not on hold.
        boolean cancelled = messages.stream().anyMatch(m ->
            m.getMessageType() == ReservationInboxEntry.MessageType.CANCEL
            && m.getStatus() == ReservationInboxEntry.Status.APPLIED);
        if (cancelled) {
            return Optional.of(new ReservationOutcome("CANCELLED", bookingRef, null, false));
        }

        boolean confirmed = messages.stream().anyMatch(m ->
            m.getMessageType() == ReservationInboxEntry.MessageType.MODIFY
            && m.getStatus() == ReservationInboxEntry.Status.APPLIED);
        if (confirmed) {
            return Optional.of(new ReservationOutcome("CONFIRMED", bookingRef, null, false));
        }

        boolean held = messages.stream().anyMatch(m ->
            m.getMessageType() == ReservationInboxEntry.MessageType.CREATE
            && m.getStatus() == ReservationInboxEntry.Status.APPLIED);
        if (held) {
            // OCTO's term for a reservation that exists but has not been paid for.
            return Optional.of(new ReservationOutcome("ON_HOLD", bookingRef, null, false));
        }

        // Nothing applied yet. The latest message's own state is the honest answer, and
        // whether it is still worth asking again is the difference that matters: a
        // REJECTED reservation will never become a booking, while a RECEIVED one is
        // waiting on the next sweep.
        return Optional.of(switch (latest.getStatus()) {
            case RECEIVED -> new ReservationOutcome("PENDING", null, null, true);
            case FAILED -> new ReservationOutcome("PENDING", null,
                reasonOr(latest, "Processing failed; it will be retried."), true);
            case REJECTED -> new ReservationOutcome("REJECTED", null,
                reasonOr(latest, "The reservation was refused."), false);
            case SUPERSEDED -> new ReservationOutcome("SUPERSEDED", null,
                reasonOr(latest, "A later message overtook this one."), false);
            case APPLIED -> new ReservationOutcome("PENDING", bookingRef, null, true);
        });
    }

    private static String reasonOr(ReservationInboxEntry entry, String fallback) {
        String reason = entry.getRejectReason();
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    /**
     * Refused for a legitimate reason — the slot was taken, the venue is closed. Distinct
     * from FAILED, which means processing errored and is worth retrying. Conflating them
     * would either retry a rejection forever or abandon a recoverable error.
     */
    @Transactional
    public ReservationInboxEntry markRejected(Long entryId, String reason) {
        ReservationInboxEntry entry = inboxRepository.findById(entryId)
            .orElseThrow(() -> new BusinessException("Unknown inbox entry: " + entryId));
        entry.setStatus(ReservationInboxEntry.Status.REJECTED);
        entry.setRejectReason(reason);
        entry.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
        log.warn("Inbox {} rejected: {}", entryId, reason);
        return inboxRepository.save(entry);
    }

    /**
     * The venue's inbound messages, newest first.
     *
     * <p>Scoped through the venue's OWN connections. Inbox rows key on
     * {@code connection_id}, not {@code binge_id}, so the tenancy boundary has to be
     * derived rather than filtered on directly — and deriving it from the connections a
     * venue actually owns is what makes it a boundary rather than a convention.
     * A venue with no connections gets an empty list without a query.
     */
    public List<com.skbingegalaxy.distribution.dto.InboxEntryDto> listForBinge(Long bingeId,
                                                                              int limit) {
        List<Long> connectionIds = connectionRepository.findByBingeIdOrderByCreatedAtDesc(bingeId)
            .stream().map(Connection::getId).toList();
        if (connectionIds.isEmpty()) return List.of();

        var destinations = destinationRepository.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(Destination::getCode, d -> d));

        return inboxRepository.findByConnectionIdInOrderByReceivedAtDesc(
                connectionIds,
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
            .getContent().stream()
            .map(e -> {
                Destination d = destinations.get(e.getDestinationCode());
                return com.skbingegalaxy.distribution.dto.InboxEntryDto.builder()
                    .id(e.getId())
                    .connectionId(e.getConnectionId())
                    .destinationCode(e.getDestinationCode())
                    .destinationName(d == null ? e.getDestinationCode() : d.getDisplayName())
                    .externalRef(e.getExternalRef())
                    .messageType(e.getMessageType())
                    .status(e.getStatus())
                    .orderingBasis(e.getOrderingBasis())
                    .externalSequence(e.getExternalSequence())
                    .receivedAt(e.getReceivedAt())
                    .processedAt(e.getProcessedAt())
                    .bookingRef(e.getBookingRef())
                    .rejectReason(e.getRejectReason())
                    .build();
            })
            .toList();
    }

    /**
     * Processing errored. Distinct from {@link #markRejected} — this one IS worth
     * retrying, and {@link #retry} will only requeue a message in this state.
     *
     * <p>The reason shares {@code reject_reason} with a refusal rather than taking a
     * column of its own: an operator needs one "why" field, and the status already
     * carries the difference between "refused" and "errored".
     */
    @Transactional
    public ReservationInboxEntry markFailed(Long entryId, String reason) {
        ReservationInboxEntry entry = inboxRepository.findById(entryId)
            .orElseThrow(() -> new BusinessException("Unknown inbox entry: " + entryId));
        entry.setStatus(ReservationInboxEntry.Status.FAILED);
        entry.setRejectReason(truncateReason(reason));
        entry.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
        log.warn("Inbox {} FAILED (retryable): {}", entryId, reason);
        return inboxRepository.save(entry);
    }

    /** {@code reject_reason} is varchar(500); a verbose provider error must not break the write. */
    static String truncateReason(String reason) {
        if (reason == null) return null;
        return reason.length() <= 500 ? reason : reason.substring(0, 497) + "...";
    }

    /**
     * Requeue a FAILED message for another attempt.
     *
     * <p>Only FAILED. A REJECTED message was legitimately refused — the slot was taken —
     * and retrying it would either fail identically or, worse, succeed later against a
     * slot someone else has since booked. A SUPERSEDED message is older than what is
     * already applied, so retrying it is the cancel-resurrection bug by hand.
     */
    @Transactional
    public ReservationInboxEntry retry(Long bingeId, Long entryId) {
        ReservationInboxEntry entry = ownedEntry(bingeId, entryId);
        if (entry.getStatus() != ReservationInboxEntry.Status.FAILED) {
            throw new BusinessException(
                "Only a FAILED message can be retried; this one is " + entry.getStatus() + ".");
        }
        entry.setStatus(ReservationInboxEntry.Status.RECEIVED);
        entry.setRejectReason(null);
        entry.setProcessedAt(null);
        log.info("Binge {} requeued inbox entry {}", bingeId, entryId);
        return inboxRepository.save(entry);
    }

    /** Scoped through the owning connection — never by entry id alone. */
    private ReservationInboxEntry ownedEntry(Long bingeId, Long entryId) {
        ReservationInboxEntry entry = inboxRepository.findById(entryId)
            .orElseThrow(() -> new com.skbingegalaxy.common.exception.ResourceNotFoundException(
                "InboxEntry", "id", entryId));
        connectionRepository.findByIdAndBingeId(entry.getConnectionId(), bingeId)
            .orElseThrow(() -> new com.skbingegalaxy.common.exception.ResourceNotFoundException(
                "InboxEntry", "id", entryId));
        return entry;
    }
}
