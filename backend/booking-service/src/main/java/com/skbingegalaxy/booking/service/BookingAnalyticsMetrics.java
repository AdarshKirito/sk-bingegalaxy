package com.skbingegalaxy.booking.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Item 27 — production-grade business funnel metrics for the booking flow.
 *
 * <p>Mirrors {@code com.skbingegalaxy.payment.service.PaymentMetrics} so the
 * Prometheus dashboard can join payment + booking signals on the same series
 * naming convention ({@code skbg_booking_*}).
 *
 * <p><b>Funnel stages</b> (matches the spec):
 * <ul>
 *   <li>{@code skbg_booking_funnel_total{stage="started|step1|step2|step3|created"}}
 *       — wizard progression. {@code started} is incremented on the first
 *       step the customer reaches; {@code created} is incremented from the
 *       service when the row hits the DB.</li>
 *   <li>{@code skbg_booking_lifecycle_total{stage="confirmed|cancelled|rescheduled|completed"}}
 *       — lifecycle transitions, incremented from the publishing sites.</li>
 *   <li>{@code skbg_booking_payment_total{stage="started|success|failed"}}
 *       — payment-side counters duplicated locally so the booking dashboard
 *       can compute a single funnel without cross-service joins.</li>
 * </ul>
 *
 * <p>Counters are tagless beyond {@code stage} on purpose: cardinality stays
 * bounded so Prometheus can index without retention pressure. Per-binge
 * breakdowns are available via the existing booking-summary REST endpoints
 * which read from the DB.
 */
@Component
public class BookingAnalyticsMetrics {

    private static final String FUNNEL = "skbg_booking_funnel_total";
    private static final String LIFECYCLE = "skbg_booking_lifecycle_total";
    private static final String PAYMENT = "skbg_booking_payment_total";

    private final MeterRegistry registry;

    public BookingAnalyticsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Wizard funnel ─────────────────────────────────────────────────────
    public void funnelStarted()  { stage(FUNNEL, "started"); }
    public void funnelStep1()    { stage(FUNNEL, "step1_completed"); }
    public void funnelStep2()    { stage(FUNNEL, "step2_completed"); }
    public void funnelStep3()    { stage(FUNNEL, "step3_completed"); }
    public void funnelCreated()  { stage(FUNNEL, "created"); }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    public void lifecycleConfirmed()  { stage(LIFECYCLE, "confirmed"); }
    public void lifecycleCancelled()  { stage(LIFECYCLE, "cancelled"); }
    public void lifecycleRescheduled(){ stage(LIFECYCLE, "rescheduled"); }
    public void lifecycleCompleted()  { stage(LIFECYCLE, "completed"); }

    // ── Payment side ──────────────────────────────────────────────────────
    public void paymentStarted() { stage(PAYMENT, "started"); }
    public void paymentSuccess() { stage(PAYMENT, "success"); }
    public void paymentFailed()  { stage(PAYMENT, "failed"); }

    /**
     * Generic ingest hook for the {@code POST /analytics/funnel} endpoint —
     * front-end emits a known stage name; we map to the appropriate counter
     * here and silently drop unknowns to keep the cardinality fixed.
     */
    public void recordClientFunnelStage(String stage) {
        if (stage == null) return;
        switch (stage.toLowerCase()) {
            case "booking_started"            -> funnelStarted();
            case "booking_step_1_completed"   -> funnelStep1();
            case "booking_step_2_completed"   -> funnelStep2();
            case "booking_step_3_completed"   -> funnelStep3();
            case "payment_started"            -> paymentStarted();
            // The rest (created, success, failed, confirmed, cancelled,
            // rescheduled, completed) are emitted server-side from the
            // authoritative state-change site, so we ignore client copies
            // to avoid double-counting.
            default -> { /* drop unknown — keeps cardinality fixed */ }
        }
    }

    private void stage(String name, String value) {
        Counter.builder(name).tags(Tags.of("stage", value)).register(registry).increment();
    }
}
