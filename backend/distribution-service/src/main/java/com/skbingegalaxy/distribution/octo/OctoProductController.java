package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import com.skbingegalaxy.distribution.entity.ListingMapping;
import com.skbingegalaxy.distribution.repository.ConnectionDestinationRepository;
import com.skbingegalaxy.distribution.repository.ListingMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a reseller may sell — the other half of the supplier surface.
 *
 * <p>Without this a reseller can book but cannot discover, so the channel sells nothing.
 * Like the booking endpoints it is supplier-hosted: no provider agreement gates building
 * it, which is the property the OCTO-first sequencing was chosen for.
 *
 * <p><b>Only LIVE listings are returned.</b> A listing that is DRAFT, BLOCKED or PAUSED
 * is one the venue has not finished or has deliberately withdrawn; publishing it to a
 * reseller would put inventory on sale that the readiness rules, the
 * {@code ck_live_requires_ready} CHECK and the operator all agree is not ready.
 *
 * <p><b>Availability deliberately not here yet.</b> Risk DIST-R6: resellers poll
 * calendars for 365 days across every product, and a naive implementation melts
 * booking-service. It needs the coarse {@code availability/calendar} versus fine
 * {@code availability} split honoured properly, a dedicated cached read path and
 * per-reseller rate limits — a design, not an endpoint bolted onto this one.
 */
@RestController
@RequestMapping("/api/v1/distribution/octo")
@RequiredArgsConstructor
@Slf4j
public class OctoProductController {

    private final ResellerAuthenticator resellerAuthenticator;
    private final ConnectionDestinationRepository connectionDestinationRepository;
    private final ListingMappingRepository listingRepository;

    @GetMapping("/products")
    public ResponseEntity<?> products(
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<Connection> connection = resellerAuthenticator.authenticate(auth);
        if (connection.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "INVALID_TOKEN",
                             "errorMessage", "The presented token is not valid."));
        }

        Connection c = connection.get();
        // Scoped to the destinations THIS connection reaches. A reseller sees only what
        // its own key is for — the same boundary the console enforces, applied to a
        // caller that is another company's system rather than a person.
        List<Long> reachable = connectionDestinationRepository.findByConnectionId(c.getId())
            .stream()
            .filter(ConnectionDestination::isEnabled)
            .map(ConnectionDestination::getId)
            .toList();
        if (reachable.isEmpty()) return okWithCache(List.of());

        List<Map<String, Object>> products = listingRepository
            .findByConnectionDestinationIdIn(reachable).stream()
            .filter(l -> l.getPublishState() == ListingMapping.PublishState.LIVE)
            .map(this::toOctoProduct)
            .toList();

        log.debug("OCTO products for connection {}: {} live listing(s)", c.getId(), products.size());
        return okWithCache(products);
    }

    /**
     * A short TTL, sent explicitly. Resellers poll hard (DIST-R6), and a cache header is
     * the cheapest defence that works before any server-side cache exists — it is also
     * the one a well-behaved reseller actually honours.
     */
    private ResponseEntity<?> okWithCache(Object body) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(body);
    }

    /**
     * OCTO 1.0 product shape, only the fields this platform can answer honestly.
     *
     * <p>{@code pricingPer: BOOKING} and {@code availabilityType: START_TIME} are not
     * defaults — they are what whole-space private hire IS. The venue is sold exclusively
     * for a window, so the price does not scale with guests and availability is a set of
     * permitted start times rather than opening hours with a unit count.
     */
    private Map<String, Object> toOctoProduct(ListingMapping listing) {
        return Map.of(
            "id", listing.getExternalProductId() != null
                ? listing.getExternalProductId()
                : String.valueOf(listing.getEventTypeId()),
            "internalName", "event-type-" + listing.getEventTypeId(),
            // Exclusive-use hire: vacancies come from room concurrency, never guest count.
            "availabilityType", "START_TIME",
            "pricingPer", "BOOKING",
            "deliveryFormats", List.of("VOUCHER"),
            "options", listing.getExternalOptionIds() == null
                ? List.of()
                : List.of(listing.getExternalOptionIds()));
    }
}
