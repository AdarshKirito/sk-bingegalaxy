package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.client.AvailabilityClient;
import com.skbingegalaxy.distribution.client.EventTypeClient;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import com.skbingegalaxy.distribution.entity.ListingMapping;
import com.skbingegalaxy.distribution.repository.ConnectionDestinationRepository;
import com.skbingegalaxy.distribution.repository.ListingMappingRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * OCTO availability — the last endpoint before a channel can actually transact, and the
 * one carrying risk DIST-R6.
 *
 * <p><b>What was wrong here, and why none of it showed up in a test.</b> Both endpoints
 * proxied {@code DayAvailabilityDto} straight from availability-service. Every individual
 * piece worked; together they meant:
 *
 * <ol>
 *   <li><b>No {@code availabilityId} was ever emitted</b>, and the booking endpoint
 *       requires one. The two halves of the supplier API did not meet, so no third party
 *       could complete a booking with data this API had given it — only someone who knew
 *       the internal token format by reading the source.</li>
 *   <li><b>{@code productId} was ignored.</b> Every product returned the venue's whole
 *       calendar, including event types the reseller was never offered.</li>
 *   <li><b>The coarse/fine split was collapsed.</b> The javadoc claimed it was "honoured,
 *       not collapsed"; in fact {@code calendar} and {@code availability} returned
 *       byte-identical payloads, so the expensive per-slot shape was served for exactly
 *       the wide-window query the split exists to keep cheap. That IS risk DIST-R6.</li>
 *   <li><b>Internal shape and {@code blockedSlots} leaked</b> — coupling a third party to
 *       availability-service's model, and telling them when the venue is deliberately
 *       closed off.</li>
 * </ol>
 *
 * <p><b>No availability is stored here.</b> Every answer is fetched at read time. A
 * cached copy would become a second inventory truth and reintroduce the oversell class
 * the V81 database backstop exists to prevent.
 */
@RestController
@RequestMapping("/api/v1/distribution/octo")
@RequiredArgsConstructor
@Slf4j
public class OctoAvailabilityController {

    private final ResellerAuthenticator resellerAuthenticator;
    private final ResellerRateLimiter rateLimiter;
    private final AvailabilityClient availabilityClient;
    private final EventTypeClient eventTypeClient;
    private final ConnectionDestinationRepository connectionDestinationRepository;
    private final ListingMappingRepository listingRepository;

    @Data
    public static class AvailabilityRequest {
        private String productId;
        private String optionId;
        private LocalDate localDateStart;
        private LocalDate localDateEnd;
        /** Fine queries may name a single day instead of a range. */
        private LocalDate localDate;
    }

    /**
     * Coarse: which days have anything at all.
     *
     * <p>Genuinely coarse now — one object per day, no per-slot detail. That is the whole
     * reason OCTO defines two endpoints, and it is what keeps a 365-day query from
     * costing what a 365-day slot dump costs.
     */
    @PostMapping("/availability/calendar")
    public ResponseEntity<?> calendar(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody AvailabilityRequest request) {

        Optional<Connection> connection = resellerAuthenticator.authenticate(auth);
        if (connection.isEmpty()) return unauthorized();
        ResponseEntity<?> throttled = rateLimiter.check(connection.get());
        if (throttled != null) return throttled;

        Optional<ListingMapping> listing = resolveListing(connection.get(), request.getProductId());
        if (listing.isEmpty()) return unknownProduct(request.getProductId());

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

        List<Map<String, Object>> days = new ArrayList<>();
        for (Map<String, Object> day : availabilityClient.calendar(
                connection.get().getBingeId(), window.from(), window.to())) {
            Object date = day.get("date");
            if (date == null) continue;
            boolean open = !truthy(day.get("closed")) && !truthy(day.get("fullyBlocked"))
                && !freeSlotStarts(day).isEmpty();
            days.add(Map.of("localDate", String.valueOf(date), "available", open));
        }
        return cached(days, Duration.ofMinutes(5));
    }

    /**
     * Fine: every window a reseller may actually buy, each with the
     * {@code id} it must send back to book it.
     */
    @PostMapping("/availability")
    public ResponseEntity<?> availability(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody AvailabilityRequest request) {

        Optional<Connection> connection = resellerAuthenticator.authenticate(auth);
        if (connection.isEmpty()) return unauthorized();
        ResponseEntity<?> throttled = rateLimiter.check(connection.get());
        if (throttled != null) return throttled;

        Optional<ListingMapping> listing = resolveListing(connection.get(), request.getProductId());
        if (listing.isEmpty()) return unknownProduct(request.getProductId());

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

        Long bingeId = connection.get().getBingeId();
        Optional<EventTypeClient.BookingRules> rules =
            eventTypeClient.rulesFor(bingeId, listing.get().getEventTypeId());
        if (rules.isEmpty()) {
            // Refused rather than defaulted. Inventing a duration would publish windows
            // the venue never agreed to sell.
            log.warn("No booking rules for event type {} on binge {} — availability refused",
                listing.get().getEventTypeId(), bingeId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "UNAVAILABLE",
                             "errorMessage", "Availability is temporarily unavailable for this product."));
        }

        List<Integer> durations = OctoAvailabilityPolicy.bookableDurations(
            rules.get().permittedDurations(), rules.get().minHours(), rules.get().maxHours());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> day : availabilityClient.slots(bingeId, window.from(), window.to())) {
            Object date = day.get("date");
            if (date == null || truthy(day.get("closed")) || truthy(day.get("fullyBlocked"))) continue;

            for (OctoAvailabilityPolicy.Availability a : OctoAvailabilityPolicy.bookableWindows(
                    LocalDate.parse(String.valueOf(date)), freeSlotStarts(day), durations)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", a.id());
                item.put("localDateTimeStart", a.localDateTimeStart().toString());
                item.put("localDateTimeEnd", a.localDateTimeEnd().toString());
                item.put("allDay", false);
                item.put("available", a.available());
                item.put("status", a.status());
                item.put("vacancies", a.vacancies());
                item.put("capacity", a.vacancies());
                out.add(item);
            }
        }

        // A shorter TTL than the calendar: this is the answer a reseller books against,
        // and stale detail sells a slot that is already gone.
        return cached(out, Duration.ofMinutes(1));
    }

    /**
     * The listing this product id names, scoped to what THIS connection reaches.
     *
     * <p>Scoping is the tenancy boundary, not a nicety: without it a reseller holding a
     * key for one venue could name another venue's product and read its calendar.
     */
    private Optional<ListingMapping> resolveListing(Connection connection, String productId) {
        if (productId == null || productId.isBlank()) return Optional.empty();

        List<Long> reachable = connectionDestinationRepository.findByConnectionId(connection.getId())
            .stream()
            .filter(ConnectionDestination::isEnabled)
            .map(ConnectionDestination::getId)
            .toList();
        if (reachable.isEmpty()) return Optional.empty();

        return listingRepository.findByConnectionDestinationIdIn(reachable).stream()
            .filter(l -> l.getPublishState() == ListingMapping.PublishState.LIVE)
            .filter(l -> productId.equals(l.getExternalProductId())
                      || productId.equals(String.valueOf(l.getEventTypeId())))
            .findFirst();
    }

    /** Minutes-from-midnight of each free 30-minute cell, from availability-service's day view. */
    @SuppressWarnings("unchecked")
    private static Set<Integer> freeSlotStarts(Map<String, Object> day) {
        Object slots = day.get("availableSlots");
        if (!(slots instanceof List<?> list)) return Set.of();
        Set<Integer> starts = new TreeSet<>();
        for (Object slot : list) {
            if (!(slot instanceof Map<?, ?> m)) continue;
            // `available` is already true for everything in availableSlots, but it is
            // read rather than assumed: the two disagreeing would mean selling a slot
            // availability-service considers taken.
            Object available = m.get("available");
            if (available != null && !truthy(available)) continue;
            Object startMinute = m.get("startMinute");
            if (startMinute instanceof Number n) starts.add(n.intValue());
        }
        return starts;
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private ResponseEntity<?> cached(Object body, Duration ttl) {
        return ResponseEntity.ok()
            // Private, not public: the response is scoped to the authenticated
            // connection, so a shared cache keyed on the URL alone would serve one
            // reseller another's venue.
            .cacheControl(CacheControl.maxAge(ttl).cachePrivate())
            .header("Vary", "Authorization")
            .body(body);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "INVALID_TOKEN",
                         "errorMessage", "The presented token is not valid."));
    }

    private ResponseEntity<?> unknownProduct(String productId) {
        // Named explicitly. Returning an empty calendar instead would read as "the venue
        // is fully booked for a year", which is the same answer a reseller gets for a
        // product it is not entitled to — and it would hide a misconfiguration for weeks.
        return ResponseEntity.badRequest().body(Map.of(
            "error", "BAD_REQUEST",
            "errorMessage", "Unknown or unavailable productId: " + productId));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "BAD_REQUEST", "errorMessage", message));
    }
}
