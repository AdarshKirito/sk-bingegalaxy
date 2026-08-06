package com.skbingegalaxy.distribution.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Reads availability from the service that owns it.
 *
 * <p><b>Nothing is cached in this context.</b> A stored copy would become a second
 * inventory truth and reintroduce exactly the oversell class the V81 database backstop
 * exists to prevent — the invariant the whole distribution design is built around. The
 * defence against reseller polling is the request clamp and the response cache headers,
 * never a local mirror of somebody else's inventory.
 *
 * <p>A failure returns EMPTY rather than propagating. A reseller shown no availability
 * sells nothing for a few minutes; a reseller shown a 500 may mark the whole supplier
 * unhealthy and stop calling. Neither is good, but only one of them is recoverable
 * without a support conversation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AvailabilityClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Value("${distribution.availability.base-url:http://availability-service:8082}")
    private String baseUrl;

    /** Coarse day-level view for the calendar endpoint. */
    public List<Map<String, Object>> calendar(Long bingeId, LocalDate from, LocalDate to) {
        return fetch(bingeId, "/api/v1/availability/dates?from=" + from + "&to=" + to);
    }

    /** Fine slot-level view. availability-service answers one date at a time. */
    public List<Map<String, Object>> slots(Long bingeId, LocalDate from, LocalDate to) {
        java.util.List<Map<String, Object>> all = new java.util.ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            all.addAll(fetch(bingeId, "/api/v1/availability/slots?date=" + d));
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetch(Long bingeId, String path) {
        try {
            Map<String, Object> body = restClientBuilder.build().get()
                .uri(baseUrl + path)
                // The venue comes from the CONNECTION, so a reseller cannot read another
                // venue's calendar by asking; the header is set here, never forwarded.
                .header("X-Binge-Id", String.valueOf(bingeId))
                .header("X-Internal-Secret", internalApiSecret)
                .retrieve()
                .body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof List<?> list) return (List<Map<String, Object>>) list;
            if (data instanceof Map<?, ?> single) return List.of((Map<String, Object>) single);
            return List.of();
        } catch (Exception e) {
            log.warn("Availability read failed for binge {} ({}): {}", bingeId, path, e.toString());
            return List.of();
        }
    }
}
