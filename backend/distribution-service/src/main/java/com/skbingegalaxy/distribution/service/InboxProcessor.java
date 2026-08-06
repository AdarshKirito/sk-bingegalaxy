package com.skbingegalaxy.distribution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.distribution.client.BookingIngestClient;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.ListingMapping;
import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
import com.skbingegalaxy.distribution.repository.ListingMappingRepository;
import com.skbingegalaxy.distribution.repository.ReservationInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Turns RECEIVED inbox entries into canonical bookings.
 *
 * <p><b>The link that was missing.</b> The OCTO endpoints recorded every reservation
 * faithfully and nothing consumed them, so a reseller could reserve, confirm, and get an
 * acknowledgement for a booking that never existed. An API that accepts reservations and
 * silently drops them is worse than one that refuses: the reseller sells the slot, the
 * traveller arrives, and the venue has no record.
 *
 * <p><b>Every outcome is terminal and distinguishable.</b> APPLIED with a booking ref,
 * REJECTED for a business refusal that retrying cannot fix, or FAILED for a transport
 * error that retrying can. Collapsing the last two — the easy mistake — either retries a
 * rejection forever or abandons a recoverable reservation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboxProcessor {

    private final ReservationInboxRepository inboxRepository;
    private final ConnectionRepository connectionRepository;
    private final ListingMappingRepository listingRepository;
    private final ReservationInboxService inboxService;
    private final BookingIngestClient bookingIngestClient;
    private final ObjectMapper objectMapper;

    /** Processed oldest-first: a CREATE must reach booking-service before its MODIFY. */
    public int processOutstanding() {
        List<ReservationInboxEntry> pending = inboxRepository
            .findByStatusInOrderByReceivedAtAsc(List.of(ReservationInboxEntry.Status.RECEIVED));
        int applied = 0;
        for (ReservationInboxEntry entry : pending) {
            if (process(entry)) applied++;
        }
        return applied;
    }

    boolean process(ReservationInboxEntry entry) {
        // CANCEL and ACKNOWLEDGE are not creations. Sending them down the ingestion path
        // would create a booking from a cancellation — the exact inversion the ordering
        // rules exist to prevent.
        if (entry.getMessageType() != ReservationInboxEntry.MessageType.CREATE
            && entry.getMessageType() != ReservationInboxEntry.MessageType.MODIFY) {
            inboxService.markRejected(entry.getId(),
                entry.getMessageType() + " is not yet applied automatically.");
            return false;
        }

        Optional<Connection> connection = connectionRepository.findById(entry.getConnectionId());
        if (connection.isEmpty()) {
            inboxService.markRejected(entry.getId(), "Connection no longer exists.");
            return false;
        }
        Long bingeId = connection.get().getBingeId();

        JsonNode payload;
        try {
            payload = objectMapper.readTree(entry.getPayloadJson());
        } catch (Exception e) {
            // Unparseable is REJECTED, not FAILED: re-reading the same bytes will fail
            // identically, so retrying it forever helps nobody.
            inboxService.markRejected(entry.getId(), "Payload could not be parsed.");
            return false;
        }

        // The reseller names a product; only a LIVE listing may be sold. Resolving the
        // event type through the mapping — rather than trusting an id in the payload —
        // is what stops a reseller booking inventory it was never offered.
        Long eventTypeId = resolveEventType(bingeId, text(payload, "productId"));
        if (eventTypeId == null) {
            inboxService.markRejected(entry.getId(),
                "No live listing matches product '" + text(payload, "productId") + "'.");
            return false;
        }

        LocalDate date = parseDate(text(payload, "localDate"));
        LocalTime start = parseTime(text(payload, "startTime"));
        if (date == null || start == null) {
            inboxService.markRejected(entry.getId(),
                "Reservation is missing a usable date or start time.");
            return false;
        }

        BookingIngestClient.Result result = bookingIngestClient.ingest(
            entry.getDestinationCode().toLowerCase(java.util.Locale.ROOT),
            entry.getExternalRef(), bingeId, eventTypeId, date, start,
            intOrNull(payload, "durationMinutes"),
            payload.hasNonNull("guests") ? payload.get("guests").asInt() : 1,
            text(payload, "guestName"), text(payload, "guestEmail"));

        if (result instanceof BookingIngestClient.Result.Accepted accepted) {
            // The settlement is created inside markApplied, from the connection's terms.
            inboxService.markApplied(entry.getId(), accepted.bookingRef(), bingeId,
                text(payload, "currency"), longOrZero(payload, "grossMinor"));
            log.info("Inbox {} APPLIED -> booking {} ({})", entry.getId(),
                accepted.bookingRef(), accepted.created() ? "created" : "already recorded");
            return true;
        }
        if (result instanceof BookingIngestClient.Result.Rejected rejected) {
            inboxService.markRejected(entry.getId(), rejected.reason());
            return false;
        }

        // FAILED stays retryable and keeps its RECEIVED status so the next sweep picks
        // it up; the recovery console can also requeue it by hand.
        BookingIngestClient.Result.Failed failed = (BookingIngestClient.Result.Failed) result;
        entry.setStatus(ReservationInboxEntry.Status.FAILED);
        entry.setRejectReason(failed.reason());
        inboxRepository.save(entry);
        return false;
    }

    private Long resolveEventType(Long bingeId, String productId) {
        if (productId == null) return null;
        return listingRepository.findByBingeId(bingeId).stream()
            .filter(l -> l.getPublishState() == ListingMapping.PublishState.LIVE)
            .filter(l -> productId.equals(l.getExternalProductId())
                      || productId.equals(String.valueOf(l.getEventTypeId())))
            .map(ListingMapping::getEventTypeId)
            .findFirst()
            .orElse(null);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private static long longOrZero(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : 0L;
    }

    private static LocalDate parseDate(String s) {
        try { return s == null ? null : LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private static LocalTime parseTime(String s) {
        try { return s == null ? null : LocalTime.parse(s); } catch (Exception e) { return null; }
    }
}
