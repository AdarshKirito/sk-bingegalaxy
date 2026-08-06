package com.skbingegalaxy.distribution.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hands a channel reservation to booking-service, which owns bookings.
 *
 * <p><b>This is the only way a distribution reservation becomes a booking.</b> Nothing
 * here writes to a booking table: the canonical path applies tenant checks, advisory
 * locking, the database occupancy trigger, pricing, tax and cancellation snapshots, the
 * state machine, event history and the outbox. Reproducing any of that here would create
 * a second booking truth — the failure this whole bounded context is designed to avoid.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingIngestClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Value("${distribution.booking.base-url:http://booking-service:8083}")
    private String baseUrl;

    /** Outcome of one ingestion attempt, distinguishing the three cases that matter. */
    public sealed interface Result {
        /** Booking exists. {@code created} is false when this was a redelivery. */
        record Accepted(String bookingRef, boolean created) implements Result {}
        /** Legitimately refused — the slot was taken. Not retryable. */
        record Rejected(String reason) implements Result {}
        /** Transport or server error. Retryable. */
        record Failed(String reason) implements Result {}
    }

    public Result ingest(String externalSource, String externalRef, Long bingeId,
                         Long eventTypeId, LocalDate date, LocalTime startTime,
                         Integer durationMinutes, int guests,
                         String guestName, String guestEmail) {

        Map<String, Object> body = new HashMap<>();
        body.put("externalSource", externalSource);
        body.put("externalRef", externalRef);
        body.put("bingeId", bingeId);
        body.put("eventTypeId", eventTypeId);
        body.put("bookingDate", String.valueOf(date));
        body.put("startTime", String.valueOf(startTime));
        body.put("durationMinutes", durationMinutes);
        body.put("numberOfGuests", guests);
        body.put("guestName", guestName);
        body.put("guestEmail", guestEmail);

        try {
            var response = restClientBuilder.build().post()
                .uri(baseUrl + "/api/v1/bookings/internal/reservations")
                .header("X-Internal-Secret", internalApiSecret)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                // 4xx is an ANSWER, not a transport failure: booking-service refused for
                // a business reason and retrying will refuse identically. Letting the
                // default handler throw would make a rejection look retryable.
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { })
                .toEntity(Map.class);

            int status = response.getStatusCode().value();
            Map<?, ?> payload = response.getBody();

            if (status >= 400) {
                String reason = messageOf(payload).orElse("Refused with status " + status);
                log.warn("Channel reservation {} refused by booking-service: {}",
                    externalRef, reason);
                return new Result.Rejected(reason);
            }

            String bookingRef = dataField(payload, "bookingRef");
            if (bookingRef == null) {
                // A 2xx with no reference means the contract changed under us. Treating
                // it as success would mark the inbox APPLIED with nothing to point at.
                return new Result.Failed("booking-service returned no bookingRef");
            }
            // 201 = created, 200 = already recorded. The distinction is preserved
            // because a redelivery must not be reported as a new sale.
            return new Result.Accepted(bookingRef, status == 201);

        } catch (Exception e) {
            // Transport, timeout, 5xx. Retryable, and deliberately NOT rejected: a
            // network blip must not permanently discard a real reservation.
            log.warn("Channel reservation {} ingestion failed: {}", externalRef, e.toString());
            return new Result.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Cancel a reservation this channel previously delivered, addressed by the channel's
     * own reference.
     *
     * <p><b>The other direction, which had no code.</b> Reservations could arrive and
     * never leave: a traveller who cancelled on the OTA kept a live booking here, and the
     * venue held a slot for someone who was not coming — invisible to both sides, because
     * the inbox recorded the cancellation as "not yet applied automatically" and everyone
     * downstream saw a healthy row.
     *
     * <p>Same classification as {@link #ingest}: 4xx is an answer, everything else is
     * retryable. A 404 is included in the 4xx branch deliberately — a cancel for a
     * reservation booking-service has never seen will not start existing on a retry.
     */
    public Result cancel(String externalSource, String externalRef, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("externalSource", externalSource);
        body.put("externalRef", externalRef);
        body.put("reason", reason);

        try {
            var response = restClientBuilder.build().post()
                .uri(baseUrl + "/api/v1/bookings/internal/reservations/cancel")
                .header("X-Internal-Secret", internalApiSecret)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { })
                .toEntity(Map.class);

            int status = response.getStatusCode().value();
            Map<?, ?> payload = response.getBody();

            if (status >= 400) {
                String refusal = messageOf(payload).orElse("Refused with status " + status);
                log.warn("Channel cancellation {} refused by booking-service: {}",
                    externalRef, refusal);
                return new Result.Rejected(refusal);
            }

            String bookingRef = dataField(payload, "bookingRef");
            if (bookingRef == null) {
                return new Result.Failed("booking-service returned no bookingRef for the cancellation");
            }
            // `created` is false: a cancellation never creates anything. The field is
            // about what the ingestion did, and reporting true here would make a
            // cancellation read as a new sale in the log.
            return new Result.Accepted(bookingRef, false);

        } catch (Exception e) {
            log.warn("Channel cancellation {} failed: {}", externalRef, e.toString());
            return new Result.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Optional<String> messageOf(Map<?, ?> payload) {
        Object m = payload == null ? null : payload.get("message");
        return m == null ? Optional.empty() : Optional.of(String.valueOf(m));
    }

    private static String dataField(Map<?, ?> payload, String field) {
        Object data = payload == null ? null : payload.get("data");
        if (data instanceof Map<?, ?> map) {
            Object v = map.get(field);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }
}
