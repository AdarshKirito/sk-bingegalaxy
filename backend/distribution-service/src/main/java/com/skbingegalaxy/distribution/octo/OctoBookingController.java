package com.skbingegalaxy.distribution.octo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import com.skbingegalaxy.distribution.service.ReservationInboxService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * The OCTO supplier endpoint — the inbound seam I repeatedly, and wrongly, called
 * blocked.
 *
 * <p>OCTO is free and open, and <b>the supplier hosts the endpoint</b>: each reseller
 * connects with a key SK Binge issues per reseller↔supplier pair. Nothing here waits on
 * a provider agreement, a certification, or a credential somebody else controls — which
 * is precisely the property the OCTO-first strategy was chosen for, and which I had been
 * describing as its opposite.
 *
 * <p><b>This controller records; it does not decide.</b> Every call lands in the
 * reservation inbox before anything is interpreted, so a refused reservation becomes an
 * explainable row rather than a lost booking. Ordering, idempotency and supersession are
 * the inbox's job; creating the canonical booking is booking-service's.
 *
 * <p>Pinned to OCTO 1.0 (risk DIST-R10): 2.0 is in community review and is treated as an
 * additive migration rather than a moving target to chase.
 */
@RestController
@RequestMapping("/api/v1/distribution/octo")
@RequiredArgsConstructor
@Slf4j
public class OctoBookingController {

    private final ResellerAuthenticator resellerAuthenticator;
    private final ReservationInboxService inboxService;
    private final ObjectMapper objectMapper;

    /**
     * The reseller supplies the {@code uuid}, and it is the idempotency key for the
     * whole lifecycle — reservation, confirmation and cancellation all carry the same
     * one. Storing it as {@code externalRef} is what lets a redelivered message be
     * caught by the inbox's unique index instead of becoming a second booking.
     */
    @Data
    public static class OctoBookingRequest {
        private String uuid;
        private String productId;
        private String optionId;
        private String availabilityId;
        /** Provider-supplied ordering, when the reseller sends one. */
        private Long sequence;
    }

    /** Reservation — OCTO's ON_HOLD step. The slot is held, not sold. */
    @PostMapping("/bookings")
    public ResponseEntity<Map<String, Object>> reserve(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody OctoBookingRequest request) {
        return record(auth, request, ReservationInboxEntry.MessageType.CREATE, HttpStatus.CREATED);
    }

    /** Confirmation — the reseller took payment and the hold becomes a booking. */
    @PostMapping("/bookings/{uuid}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String uuid,
            @RequestBody(required = false) OctoBookingRequest body) {
        OctoBookingRequest request = body == null ? new OctoBookingRequest() : body;
        request.setUuid(uuid);
        return record(auth, request, ReservationInboxEntry.MessageType.MODIFY, HttpStatus.OK);
    }

    @DeleteMapping("/bookings/{uuid}")
    public ResponseEntity<Map<String, Object>> cancel(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String uuid,
            @RequestParam(required = false) Long sequence) {
        OctoBookingRequest request = new OctoBookingRequest();
        request.setUuid(uuid);
        request.setSequence(sequence);
        return record(auth, request, ReservationInboxEntry.MessageType.CANCEL, HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> record(
            String auth, OctoBookingRequest request,
            ReservationInboxEntry.MessageType type, HttpStatus success) {

        Optional<Connection> connection = resellerAuthenticator.authenticate(auth);
        if (connection.isEmpty()) {
            // A bad token and an unknown token are answered identically: distinguishing
            // them tells a caller whether a given key exists.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "INVALID_TOKEN",
                             "errorMessage", "The presented token is not valid."));
        }
        if (request.getUuid() == null || request.getUuid().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "BAD_REQUEST", "errorMessage", "uuid is required."));
        }

        Connection c = connection.get();
        // Destination comes from the CONNECTION, never from the request body. Letting a
        // reseller name a destination would let it claim commercial terms belonging to a
        // pairing it holds no key for.
        String destinationCode = c.getProviderCode();

        String payload;
        try {
            payload = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            // The inbox stores payloads verbatim for audit. If it cannot be serialised,
            // saying so beats storing something that is not what arrived.
            return ResponseEntity.badRequest()
                .body(Map.of("error", "BAD_REQUEST", "errorMessage", "Unreadable payload."));
        }

        ReservationInboxEntry entry = inboxService.receive(
            c.getId(), destinationCode, request.getUuid(), type,
            request.getSequence(), null, payload);

        log.info("OCTO {} for {} on connection {} -> inbox {} ({})",
            type, request.getUuid(), c.getId(), entry.getId(), entry.getStatus());

        // Reported honestly: a SUPERSEDED message is acknowledged as received, never
        // dressed up as applied. A reseller told "confirmed" for a message we set aside
        // would show a traveller a booking that does not exist.
        return ResponseEntity.status(success).body(Map.of(
            "uuid", request.getUuid(),
            "status", entry.getStatus() == ReservationInboxEntry.Status.SUPERSEDED
                ? "SUPERSEDED" : "PENDING",
            "supplierReference", String.valueOf(entry.getId())));
    }
}
