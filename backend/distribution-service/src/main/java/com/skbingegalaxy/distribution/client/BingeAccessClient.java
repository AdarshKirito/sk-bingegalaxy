package com.skbingegalaxy.distribution.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Who owns a venue, and which modules its admins may use.
 *
 * <p><b>Why distribution-service has to ask.</b> The venue arrives as the gateway's
 * {@code X-Binge-Id} header, and that header is not proof of anything: it is set from the
 * venue picker in the browser, so any authenticated admin can put any number in it. Every
 * other service that acts on a selected venue — payment-service, availability-service —
 * resolves ownership through this same internal snapshot before writing. Distribution did
 * not, which meant an admin of one venue could read, pause, revoke or re-key
 * <em>another</em> venue's sales channels by editing one header.
 *
 * <p><b>The INTERNAL snapshot, never the public one.</b> {@code /binges/{id}} strips
 * {@code adminId}, which would null every ownership check and turn this into a check that
 * always passes. That mistake has been made in this repo before, and it is silent in
 * exactly the direction that matters.
 *
 * <p>The same round trip returns the V71 denied-module list for the requesting admin, so
 * the {@code DISTRIBUTION} grant is enforced without a second call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BingeAccessClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Value("${distribution.booking.base-url:http://booking-service:8083}")
    private String baseUrl;

    /**
     * The three answers that must stay distinguishable. Collapsing {@link Unavailable}
     * into {@link NotFound} would mean a booking-service outage reads as "you do not own
     * this venue"; collapsing it into a pass would mean an outage disables the check
     * entirely, which is the failure mode being fixed here.
     */
    public sealed interface Lookup {
        record Found(Long bingeId, Long adminId, List<String> deniedModules) implements Lookup {}
        record NotFound() implements Lookup {}
        record Unavailable(String reason) implements Lookup {}
    }

    public Lookup lookup(Long bingeId, Long userId) {
        try {
            var response = restClientBuilder.build().get()
                .uri(baseUrl + "/api/v1/bookings/internal/binges/" + bingeId + "?userId=" + userId)
                .header("X-Internal-Secret", internalApiSecret)
                .retrieve()
                // A 404 is an ANSWER — there is no such venue — not a transport failure.
                // Letting the default handler throw would make it indistinguishable from
                // booking-service being down, and the two demand opposite responses.
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { })
                .toEntity(Map.class);

            if (response.getStatusCode().value() >= 400) {
                return new Lookup.NotFound();
            }
            Object data = response.getBody() == null ? null : response.getBody().get("data");
            if (!(data instanceof Map<?, ?> binge)) {
                return new Lookup.Unavailable("binge snapshot had no data");
            }
            return new Lookup.Found(
                longOrNull(binge.get("id")),
                longOrNull(binge.get("adminId")),
                stringList(binge.get("deniedModules")));

        } catch (Exception e) {
            // Fail closed at the caller: an ownership check that cannot be performed is
            // not an ownership check that passed.
            log.warn("Could not resolve binge {} for user {}: {}", bingeId, userId, e.toString());
            return new Lookup.Unavailable(e.getClass().getSimpleName());
        }
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }
}
