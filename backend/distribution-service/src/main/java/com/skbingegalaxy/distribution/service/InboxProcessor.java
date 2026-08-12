package com.skbingegalaxy.distribution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.distribution.client.BookingIngestClient;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.ListingMapping;
import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import com.skbingegalaxy.distribution.octo.AvailabilityIdCodec;
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
 *
 * <p><b>CANCEL is applied, not refused.</b> It used to be turned away with "not yet
 * applied automatically", which meant a traveller who cancelled on the OTA kept a live
 * booking here and the venue held a slot for someone who was not coming — with nothing
 * anywhere reporting a problem. It now goes to booking-service's cancellation seam,
 * addressed by the channel's own reference, never down the ingestion path: creating a
 * booking from a cancellation is the exact inversion the ordering rules exist to prevent.
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

    /**
     * How many messages one sweep will take. The drain runs every 30 seconds, so an
     * unbounded read was a full scan of every outstanding row on every tick — and on a
     * backlog it loaded the whole queue into memory to process a handful of it.
     */
    static final int MAX_BATCH = 200;

    /**
     * Processed oldest-first: a CREATE must reach booking-service before its MODIFY.
     *
     * <p><b>One bad row must not stop the queue.</b> Every message is processed inside
     * its own guard. Without it, a single entry whose processing threw — a negative
     * retail price reaching the settlement calculation was the live example — propagated
     * out of this loop and abandoned the whole sweep. The row stayed RECEIVED, stayed
     * first in a queue that is global rather than per-venue, and the next tick 30 seconds
     * later hit it again: one malformed message from one reseller silently stopped
     * reservations reaching <em>every</em> venue on the platform, while the scheduler,
     * the worker and the health endpoint all reported themselves fine.
     */
    public int processOutstanding() {
        List<ReservationInboxEntry> pending = inboxRepository
            .findByStatusInOrderByReceivedAtAsc(
                List.of(ReservationInboxEntry.Status.RECEIVED),
                org.springframework.data.domain.PageRequest.of(0, MAX_BATCH))
            .getContent();
        int applied = 0;
        for (ReservationInboxEntry entry : pending) {
            try {
                if (process(entry)) applied++;
            } catch (RuntimeException e) {
                isolate(entry, e);
            }
        }
        return applied;
    }

    /**
     * Take a row that threw out of the queue, so the sweep can continue past it.
     *
     * <p>FAILED rather than REJECTED: an exception is not a business refusal, and the
     * cause may well be transient. The row keeps its payload and its reason, stays
     * visible in the recovery console, and can be requeued by hand once the cause is
     * understood — which is the whole point of persisting messages before interpreting
     * them.
     *
     * <p>The marking is itself guarded. If the database is what is failing, marking the
     * row will fail too, and letting that escape would re-create exactly the
     * whole-sweep abort this method exists to prevent.
     */
    private void isolate(ReservationInboxEntry entry, RuntimeException cause) {
        log.error("Inbox {} threw during processing; isolating it so the sweep continues",
            entry.getId(), cause);
        try {
            inboxService.markFailed(entry.getId(),
                cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (RuntimeException marking) {
            log.error("Inbox {} could not be marked FAILED either: {}",
                entry.getId(), marking.toString());
        }
    }

    boolean process(ReservationInboxEntry entry) {
        // An ACKNOWLEDGE carries no instruction to act on. Rejected rather than left
        // RECEIVED so it does not sit in the queue being re-read on every sweep.
        if (entry.getMessageType() == ReservationInboxEntry.MessageType.ACKNOWLEDGE) {
            inboxService.markRejected(entry.getId(),
                "ACKNOWLEDGE is not yet applied automatically.");
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

        // A cancellation is emphatically NOT a creation — sending it down the ingestion
        // path would create a booking from a cancellation, the exact inversion the
        // ordering rules exist to prevent. It goes to its own endpoint, addressed by the
        // channel's own reference.
        if (entry.getMessageType() == ReservationInboxEntry.MessageType.CANCEL) {
            return applyCancellation(entry, bingeId);
        }

        // OCTO's confirmation step turns a hold into a sale and carries no reservation
        // detail — POST /bookings/{uuid}/confirm has an optional body, and resellers
        // routinely send none. Sent down the ingestion path it would be refused as "no
        // live listing matches product 'null'", so every confirmed sale would leave a
        // REJECTED row next to the booking it successfully confirmed. Nothing more is
        // needed in the PMS: the CREATE already produced the reservation.
        if (entry.getMessageType() == ReservationInboxEntry.MessageType.MODIFY
                && !describesAReservation(payload)) {
            return applyConfirmation(entry, bingeId);
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

        Window window = resolveWindow(payload);
        if (window == null) {
            inboxService.markRejected(entry.getId(),
                "Reservation is missing a usable date or start time.");
            return false;
        }

        // A price that cannot be a price is refused HERE, before a booking exists.
        // Carrying it forward meant the reservation was ingested successfully and then
        // threw in the settlement calculation on the way out — leaving a real booking
        // with no receivable, and an inbox row that re-threw on every subsequent sweep.
        // Refusing up front is the same treatment the other malformed-payload cases get,
        // and it gives the reseller something it can correct and resend.
        long retailMinor = grossMinor(payload);
        if (retailMinor < 0) {
            inboxService.markRejected(entry.getId(),
                "Reservation carries a negative price (" + retailMinor + ").");
            return false;
        }

        String guestName = guestName(payload);
        if (guestName == null || guestName.isBlank()) {
            // The venue has to know who is arriving. Refusing here, with a reason a human
            // can act on, beats creating a booking attributed to nobody — and
            // booking-service would reject the blank name anyway, one service hop later
            // and with a far less useful message.
            inboxService.markRejected(entry.getId(),
                "Reservation carries no guest name.");
            return false;
        }

        BookingIngestClient.Result result = bookingIngestClient.ingest(
            externalSource(entry),
            entry.getExternalRef(), bingeId, eventTypeId,
            window.date(), window.start(), window.durationMinutes(),
            guestCount(payload), guestName, guestEmail(payload));

        return record(entry, bingeId, payload, result);
    }

    /**
     * CANCEL. booking-service resolves the booking from
     * {@code (externalSource, externalRef)} and is idempotent, so a redelivered cancel
     * answers 200 rather than an error — which matters, because at-least-once delivery
     * guarantees the redelivery.
     */
    private boolean applyCancellation(ReservationInboxEntry entry, Long bingeId) {
        BookingIngestClient.Result result = bookingIngestClient.cancel(
            externalSource(entry), entry.getExternalRef(), bingeId,
            "Cancelled by " + entry.getDestinationCode());
        // No payload, deliberately: a cancellation must never create a receivable. Today
        // an OCTO cancel carries no pricing anyway, but a future provider adapter that
        // echoed the original price would otherwise book the sale a second time on the
        // way out. The original sale's settlement is untouched either way.
        return record(entry, bingeId, null, result);
    }

    /**
     * CONFIRM — OCTO's confirmation step, which turns a hold into a sale.
     *
     * <p><b>This used to be a bookkeeping entry and nothing more.</b> The row was marked
     * APPLIED against the booking the CREATE had produced, and the booking itself was
     * never touched. It stayed PENDING, and booking-service's pending-timeout sweep
     * auto-cancelled it about half an hour later — after the reseller had told the
     * traveller they were booked. The inbox said APPLIED, the reseller had a
     * confirmation, and the venue's calendar was empty. Nothing anywhere reported a
     * problem, which is what made it expensive.
     *
     * <p>A confirmation carries no reservation detail — {@code POST /bookings/{uuid}/
     * confirm} has an optional body and resellers routinely send none — so the booking is
     * addressed by the channel's own reference, exactly as a cancellation is.
     */
    private boolean applyConfirmation(ReservationInboxEntry entry, Long bingeId) {
        String bookingRef = appliedBookingRefFor(entry);
        if (bookingRef == null) {
            // A confirmation whose CREATE we never applied. Refused rather than guessed:
            // confirming a reservation that does not exist here would tell the reseller a
            // traveller has a booking nobody is expecting.
            inboxService.markRejected(entry.getId(),
                "No applied reservation for " + entry.getExternalRef() + " to confirm.");
            return false;
        }

        BookingIngestClient.Result result = bookingIngestClient.confirm(
            externalSource(entry), entry.getExternalRef(), bingeId);
        // No payload: a confirmation carries no price and must not create a second
        // receivable. The CREATE's settlement is the record of this sale.
        boolean ok = record(entry, bingeId, null, result);
        if (ok) {
            log.info("Inbox {} CONFIRMED booking {}", entry.getId(), bookingRef);
        }
        return ok;
    }

    /** One place where a client Result becomes an inbox status, so the two cannot drift. */
    private boolean record(ReservationInboxEntry entry, Long bingeId,
                           JsonNode payload, BookingIngestClient.Result result) {
        if (result instanceof BookingIngestClient.Result.Accepted accepted) {
            // The settlement is created inside markApplied, from the connection's terms.
            inboxService.markApplied(entry.getId(), accepted.bookingRef(), bingeId,
                currency(payload), grossMinor(payload));
            log.info("Inbox {} APPLIED -> booking {} ({})", entry.getId(),
                accepted.bookingRef(), accepted.created() ? "created" : "already recorded");
            return true;
        }
        if (result instanceof BookingIngestClient.Result.Rejected rejected) {
            inboxService.markRejected(entry.getId(), rejected.reason());
            return false;
        }

        // FAILED stays retryable; the recovery console can requeue it by hand. Written
        // through the service rather than inline so the reason is truncated to the
        // column width — a verbose transport error was long enough to throw on save,
        // inside a sweep loop that would have abandoned every message behind it.
        BookingIngestClient.Result.Failed failed = (BookingIngestClient.Result.Failed) result;
        ReservationInboxEntry saved = inboxService.markFailed(entry.getId(), failed.reason());
        // Keep the caller's instance in step: the existing tests assert on the entry
        // they passed in, and a status that only moved in the database is a status the
        // console would show correctly and every in-process reader would not.
        entry.setStatus(saved.getStatus());
        entry.setRejectReason(saved.getRejectReason());
        return false;
    }

    /**
     * The provider-neutral slug booking-service stores.
     *
     * <p>Lower-cased because {@code ChannelReservationRequest} canonicalises the same
     * way. If both {@code "SIMULATOR"} and {@code "simulator"} could be stored, the
     * unique index on {@code (external_source, external_ref)} would hold two rows and a
     * redelivery would double-book the venue.
     */
    private static String externalSource(ReservationInboxEntry entry) {
        return entry.getDestinationCode().toLowerCase(java.util.Locale.ROOT);
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

    // ── Reading the message ──────────────────────────────────────────────────
    //
    // Two payload dialects reach this class, and it must read both.
    //
    // The OCTO endpoints store the reseller's own vocabulary: an `availabilityId` naming
    // the window, a `contact` object, `unitItems`, `pricing`. Everything below used to
    // read a FLAT shape instead — localDate, startTime, guestName, grossMinor — that no
    // endpoint in this service has ever written. The result was not a crash: every OCTO
    // reservation was faithfully recorded and then REJECTED as "missing a usable date or
    // start time", so the inbox filled with refusals while the worker, the scheduler and
    // the console all reported themselves healthy.
    //
    // The flat form is kept as a fallback rather than deleted. It is the shape a
    // non-OCTO provider adapter naturally produces, and dropping it would trade one
    // silent dialect mismatch for another the first time a second provider is added.

    /** A bookable window, however the message chose to express it. */
    private record Window(LocalDate date, LocalTime start, Integer durationMinutes) {}

    /**
     * Does this message actually describe a reservation, or is it a bare lifecycle
     * signal? Both a product and a window are required — a message with one and not the
     * other is incomplete rather than minimal, and must be refused with a reason rather
     * than treated as a confirmation.
     */
    private static boolean describesAReservation(JsonNode payload) {
        return text(payload, "productId") != null && resolveWindow(payload) != null;
    }

    /**
     * The booking an earlier message for this same external reference produced.
     *
     * <p>Scoped to the connection, so one reseller's reference can never resolve to a
     * booking another reseller's message created.
     */
    private String appliedBookingRefFor(ReservationInboxEntry entry) {
        return inboxRepository
            .findByConnectionIdAndExternalRefOrderByIdAsc(
                entry.getConnectionId(), entry.getExternalRef())
            .stream()
            .filter(e -> e.getStatus() == ReservationInboxEntry.Status.APPLIED)
            .map(ReservationInboxEntry::getBookingRef)
            .filter(java.util.Objects::nonNull)
            .reduce((first, second) -> second)   // the most recent one
            .orElse(null);
    }

    private static Window resolveWindow(JsonNode payload) {
        // OCTO first: the supplier issued this token, so it is the more trustworthy of
        // the two — it cannot disagree with itself the way three separate fields can.
        var decoded = AvailabilityIdCodec.decode(text(payload, "availabilityId"));
        if (decoded.isPresent()) {
            return new Window(decoded.get().start().toLocalDate(),
                              decoded.get().start().toLocalTime(),
                              decoded.get().durationMinutes());
        }
        LocalDate date = parseDate(text(payload, "localDate"));
        LocalTime start = parseTime(text(payload, "startTime"));
        if (date == null || start == null) return null;
        return new Window(date, start, intOrNull(payload, "durationMinutes"));
    }

    /** OCTO sends {@code contact.fullName}, or the name in parts, or neither. */
    private static String guestName(JsonNode payload) {
        JsonNode contact = payload.get("contact");
        if (contact != null && !contact.isNull()) {
            String full = text(contact, "fullName");
            if (full != null && !full.isBlank()) return full.trim();
            String joined = ((orEmpty(text(contact, "firstName"))) + " "
                           + (orEmpty(text(contact, "lastName")))).trim();
            if (!joined.isEmpty()) return joined;
        }
        return text(payload, "guestName");
    }

    private static String guestEmail(JsonNode payload) {
        JsonNode contact = payload.get("contact");
        if (contact != null && !contact.isNull()) {
            String email = text(contact, "emailAddress");
            if (email != null && !email.isBlank()) return email;
        }
        return text(payload, "guestEmail");
    }

    /**
     * Whole-space private hire is priced per booking, so OCTO's unit breakdown carries no
     * pricing meaning — but the venue still needs a head count for capacity. One guest
     * when the message says nothing: a booking for zero people is not a thing.
     */
    private static int guestCount(JsonNode payload) {
        JsonNode units = payload.get("unitItems");
        if (units != null && units.isArray() && !units.isEmpty()) {
            return Math.max(1, Math.min(units.size(), 100));
        }
        return payload.hasNonNull("guests") ? Math.max(1, payload.get("guests").asInt()) : 1;
    }

    /**
     * What the CHANNEL charged, which is not necessarily what SK Binge would have —
     * the destination sets its own retail price, so this comes from the message rather
     * than from our pricing engine.
     */
    private static String currency(JsonNode payload) {
        // Null on a cancellation, which has no price to record.
        if (payload == null) return null;
        JsonNode pricing = payload.get("pricing");
        if (pricing != null && !pricing.isNull()) {
            String c = text(pricing, "currency");
            if (c != null && !c.isBlank()) return c;
        }
        return text(payload, "currency");
    }

    private static long grossMinor(JsonNode payload) {
        if (payload == null) return 0L;
        JsonNode pricing = payload.get("pricing");
        if (pricing != null && pricing.hasNonNull("retail")) {
            return pricing.get("retail").asLong();
        }
        return longOrZero(payload, "grossMinor");
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
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
