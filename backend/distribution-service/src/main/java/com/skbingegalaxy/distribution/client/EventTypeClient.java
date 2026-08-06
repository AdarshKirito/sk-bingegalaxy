package com.skbingegalaxy.distribution.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads an event type's booking rules from the service that owns them.
 *
 * <p><b>Why the rules cannot be guessed here.</b> How long a venue sells its space for is
 * a property of the event type — an explicit {@code permittedDurations} allow-list
 * (V84/B5), or a {@code minHours}..{@code maxHours} range. Without them the availability
 * endpoint would have to invent a duration, and every window it published would be a
 * claim the venue never made.
 *
 * <p>Nothing is cached. The same argument as {@link AvailabilityClient}: a stored copy of
 * someone else's rules becomes a second truth that drifts, and a venue that narrows its
 * permitted durations would keep being sold the old ones.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventTypeClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${distribution.booking.base-url:http://booking-service:8083}")
    private String baseUrl;

    /** Only the fields that decide what may be sold. */
    public record BookingRules(Long eventTypeId,
                               String name,
                               List<Integer> permittedDurations,
                               Integer minHours,
                               Integer maxHours) {}

    /**
     * The rules for one event type, or empty when it is not active for this venue.
     *
     * <p>Empty is a refusal, never a default: publishing availability for an event type
     * whose rules we could not read would put windows on sale that no venue authorised.
     */
    @SuppressWarnings("unchecked")
    public Optional<BookingRules> rulesFor(Long bingeId, Long eventTypeId) {
        try {
            Map<String, Object> body = restClientBuilder.build().get()
                .uri(baseUrl + "/api/v1/bookings/event-types")
                // The venue comes from the CONNECTION, never from the reseller's request,
                // so a reseller cannot read another venue's catalogue by asking.
                .header("X-Binge-Id", String.valueOf(bingeId))
                .retrieve()
                .body(Map.class);

            Object data = body == null ? null : body.get("data");
            if (!(data instanceof List<?> list)) return Optional.empty();

            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) continue;
                if (!String.valueOf(eventTypeId).equals(String.valueOf(m.get("id")))) continue;
                return Optional.of(new BookingRules(
                    eventTypeId,
                    m.get("name") == null ? null : String.valueOf(m.get("name")),
                    intList(m.get("permittedDurations")),
                    intOrNull(m.get("minHours")),
                    intOrNull(m.get("maxHours"))));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read event type {} for binge {}: {}", eventTypeId, bingeId, e.toString());
            return Optional.empty();
        }
    }

    private static List<Integer> intList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
            .map(EventTypeClient::intOrNull)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
