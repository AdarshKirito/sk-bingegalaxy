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
    private final ConnectionRepository connectionRepository;
    private final ConnectionDestinationRepository connectionDestinationRepository;
    private final DestinationRepository destinationRepository;

    /** Ceiling on a stored payload; mirrors {@code distribution.inbox.max-payload-bytes}. */
    public static final int MAX_PAYLOAD_BYTES = 262_144;

    /**
     * Record an inbound message and decide whether it may be applied.
     *
     * <p>Runs in its OWN transaction. If applying the message later fails, the inbox row
     * must survive to explain why — a rollback that erased the evidence would leave
     * exactly the silent loss this design exists to prevent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            null);

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

        try {
            ReservationInboxEntry saved = inboxRepository.save(entry);
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
            log.debug("Duplicate inbound message for connection {} ref {} ({})",
                connectionId, externalRef, messageType);
            return inboxRepository
                .findByConnectionIdAndExternalRefOrderByIdAsc(connectionId, externalRef)
                .stream()
                .filter(x -> x.getMessageType() == messageType)
                .reduce((first, second) -> second)
                .orElseThrow(() -> e);
        }
    }

    /** Mark a message applied once booking-service has the canonical reservation. */
    @Transactional
    public ReservationInboxEntry markApplied(Long entryId, String bookingRef) {
        ReservationInboxEntry entry = inboxRepository.findById(entryId)
            .orElseThrow(() -> new BusinessException("Unknown inbox entry: " + entryId));
        entry.setStatus(ReservationInboxEntry.Status.APPLIED);
        entry.setBookingRef(bookingRef);
        entry.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
        return inboxRepository.save(entry);
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
