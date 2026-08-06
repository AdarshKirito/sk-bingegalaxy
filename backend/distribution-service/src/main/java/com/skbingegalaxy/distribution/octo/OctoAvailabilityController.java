package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.client.AvailabilityClient;
import com.skbingegalaxy.distribution.entity.Connection;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OCTO availability — the last endpoint before a channel can actually transact, and the
 * one carrying risk DIST-R6.
 *
 * <p><b>The coarse/fine split is honoured, not collapsed.</b> OCTO defines two endpoints
 * on purpose: {@code availability/calendar} answers "which days have anything" cheaply
 * over a wide window, and {@code availability} returns every slot for a narrow one. A
 * single endpoint doing both would force the expensive shape on every query and is
 * precisely how the fan-out risk reaches production.
 *
 * <p>Every response carries a short {@code Cache-Control} and every request is clamped
 * by {@link AvailabilityRequestPolicy}. Together they bound what one polling reseller can
 * cost the availability service — which direct customer bookings also depend on. A
 * distribution feature must never be able to degrade the core product.
 *
 * <p><b>No availability is stored here.</b> Every answer is fetched from the owning
 * service at read time. A cached copy in this context would become a second inventory
 * truth and reintroduce the oversell class the V81 database backstop exists to prevent —
 * the invariant this whole bounded context is built around.
 */
@RestController
@RequestMapping("/api/v1/distribution/octo")
@RequiredArgsConstructor
@Slf4j
public class OctoAvailabilityController {

    private final ResellerAuthenticator resellerAuthenticator;
    private final AvailabilityClient availabilityClient;

    @Data
    public static class AvailabilityRequest {
        private String productId;
        private String optionId;
        private LocalDate localDateStart;
        private LocalDate localDateEnd;
        /** Fine queries may name a single day instead of a range. */
        private LocalDate localDate;
    }

    /** Coarse: which days have anything at all. Wide window, cheap per day. */
    @PostMapping("/availability/calendar")
    public ResponseEntity<?> calendar(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody AvailabilityRequest request) {

        Optional<Connection> connection = resellerAuthenticator.authenticate(auth);
        if (connection.isEmpty()) return unauthorized();

        AvailabilityRequestPolicy.Window window;
        try {
            window = AvailabilityRequestPolicy.clamp(
                request.getLocalDateStart(), request.getLocalDateEnd(),
                AvailabilityRequestPolicy.MAX_CALENDAR_DAYS);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        if (window.clamped()) {
            // Logged, and the shortened window is visible in the response dates, so a
            // reseller can page. Silently truncating would look like missing inventory.
            log.info("OCTO calendar clamped to {}..{} for connection {}",
                window.from(), window.to(), connection.get().getId());
        }

        List<Map<String, Object>> days = availabilityClient.calendar(
            connection.get().getBingeId(), window.from(), window.to());
        return cached(days, Duration.ofMinutes(5));
    }

    /** Fine: every slot for a narrow window, with the detail a reseller sells from. */
    @PostMapping("/availability")
    public ResponseEntity<?> availability(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody AvailabilityRequest request) {

        Optional<Connection> connection = resellerAuthenticator.authenticate(auth);
        if (connection.isEmpty()) return unauthorized();

        LocalDate start = request.getLocalDate() != null
            ? request.getLocalDate() : request.getLocalDateStart();
        LocalDate end = request.getLocalDate() != null
            ? request.getLocalDate() : request.getLocalDateEnd();

        AvailabilityRequestPolicy.Window window;
        try {
            window = AvailabilityRequestPolicy.clamp(
                start, end, AvailabilityRequestPolicy.MAX_DETAIL_DAYS);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        List<Map<String, Object>> slots = availabilityClient.slots(
            connection.get().getBingeId(), window.from(), window.to());
        // A shorter TTL than the calendar: this is the answer a reseller books against,
        // and stale detail sells a slot that is already gone.
        return cached(slots, Duration.ofMinutes(1));
    }

    private ResponseEntity<?> cached(Object body, Duration ttl) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(ttl).cachePublic())
            .body(body);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "INVALID_TOKEN",
                         "errorMessage", "The presented token is not valid."));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "BAD_REQUEST", "errorMessage", message));
    }
}
