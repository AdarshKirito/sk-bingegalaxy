package com.skbingegalaxy.booking.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Business-level funnel metrics that Grafana can translate into the KPIs
 * invisible to a purely infrastructure-oriented dashboard:
 *
 * <ul>
 *   <li><b>skbg_slot_hold_total{outcome}</b> — created / expired(abandoned) /
 *       converted / released. The ratio of expired÷created is the booking
 *       abandonment rate (customer started checkout but never paid).</li>
 *   <li><b>skbg_waitlist_total{outcome}</b> — joined / offered / converted /
 *       expired. The ratio of converted÷offered is the waitlist conversion rate.</li>
 *   <li><b>skbg_payment_retry_total</b> — tracked in payment-service
 *       {@code PaymentMetrics}: times a customer retried after a FAILED attempt.
 *       High rate ⇒ Razorpay configuration issues or customer card problems.</li>
 * </ul>
 *
 * Notification delivery failures are tracked separately in
 * {@code notification-service/NotificationMetrics} (skbg_notification_failure_total)
 * since delivery happens in a different process.
 *
 * All counters are lazily registered so the service starts fine in test
 * profiles that disable Prometheus.
 */
@Component
public class BookingFunnelMetrics {

    private final MeterRegistry registry;

    public BookingFunnelMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Slot hold funnel ───────────────────────────────────────────────────────

    public void holdCreated()   { count("skbg_slot_hold_total", "outcome", "created"); }

    /** Hold TTL elapsed without the customer proceeding to booking — abandonment. */
    public void holdExpired()   { count("skbg_slot_hold_total", "outcome", "expired"); }

    /** Customer completed payment and booking was confirmed from this hold. */
    public void holdConverted() { count("skbg_slot_hold_total", "outcome", "converted"); }

    /** Customer explicitly released the hold (back button, changed mind). */
    public void holdReleased()  { count("skbg_slot_hold_total", "outcome", "released"); }

    // ── Waitlist funnel ────────────────────────────────────────────────────────

    public void waitlistJoined()    { count("skbg_waitlist_total", "outcome", "joined"); }

    /** Slot became available and entry was promoted to OFFERED with a notification. */
    public void waitlistOffered()   { count("skbg_waitlist_total", "outcome", "offered"); }

    /** Customer acted on the offer and completed a booking. */
    public void waitlistConverted() { count("skbg_waitlist_total", "outcome", "converted"); }

    /** Offer window expired before customer booked. */
    public void waitlistExpired()   { count("skbg_waitlist_total", "outcome", "expired"); }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void count(String name, String tagKey, String tagValue) {
        Counter.builder(name).tags(Tags.of(tagKey, tagValue)).register(registry).increment();
    }
}
