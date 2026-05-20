package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.service.BookingAnalyticsMetrics;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Item 27 — front-end booking-funnel ingestion.
 *
 * <p>The wizard fires {@code POST /api/v1/bookings/analytics/funnel} on every
 * step boundary so we get accurate drop-off curves for the pre-create stages
 * ({@code booking_started}, {@code booking_step_1_completed},
 * {@code booking_step_2_completed}, {@code booking_step_3_completed},
 * {@code payment_started}). Post-create stages (created / confirmed / paid /
 * cancelled / completed) are emitted server-side from the publishing path —
 * see {@link com.skbingegalaxy.booking.service.BookingService#publishBookingEvent}
 * and {@link com.skbingegalaxy.booking.listener.PaymentEventListener} — so a
 * tampered or replayed client request cannot inflate them.
 *
 * <p>Endpoint is intentionally unauthenticated-friendly: the wizard runs for
 * guests too. The unknown-stage path drops silently so cardinality is fixed.
 */
@RestController
@RequestMapping("/api/v1/bookings/analytics")
@RequiredArgsConstructor
@Slf4j
public class BookingAnalyticsController {

    private final BookingAnalyticsMetrics metrics;

    @PostMapping("/funnel")
    public ResponseEntity<ApiResponse<Void>> recordFunnelStage(
            @RequestBody Map<String, Object> body) {
        Object raw = body != null ? body.get("stage") : null;
        String stage = raw != null ? raw.toString() : null;
        if (stage != null && !stage.isBlank()) {
            metrics.recordClientFunnelStage(stage);
            log.debug("analytics.funnel stage={}", stage);
        }
        return ResponseEntity.ok(ApiResponse.ok("recorded", null));
    }
}
