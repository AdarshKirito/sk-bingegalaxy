package com.skbingegalaxy.notification.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Production-grade observability counters for the notification service.
 *
 * <ul>
 *   <li><b>skbg_notification_sent_total{channel}</b> — successful deliveries per channel.
 *       Useful as denominator for failure-rate calculations.</li>
 *   <li><b>skbg_notification_failure_total{channel}</b> — failed deliveries per channel.
 *       The Grafana panel "Notification Delivery Failures" queries this metric.
 *       Alert when rate spikes — means customers are not receiving booking confirmations,
 *       payment receipts, or waitlist offers.</li>
 *   <li><b>skbg_notification_permanent_failure_total{channel}</b> — notifications that
 *       exhausted all retries and will never be delivered. These require manual ops review.</li>
 * </ul>
 *
 * All counters are lazily registered so the service starts fine in test profiles
 * that disable Prometheus.
 */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Successful delivery for a channel (email / sms / push / whatsapp). */
    public void deliverySent(String channel) {
        count("skbg_notification_sent_total", channel);
    }

    /**
     * Delivery attempt failed for a channel. Called on every failed attempt
     * (including transient failures that will be retried).
     */
    public void deliveryFailed(String channel) {
        count("skbg_notification_failure_total", channel);
    }

    /**
     * Notification exhausted all retry attempts and will never be delivered.
     * Ops must review these — the customer was never notified of a critical event.
     */
    public void permanentFailure(String channel) {
        count("skbg_notification_permanent_failure_total", channel);
    }

    private void count(String name, String channel) {
        Counter.builder(name)
            .tags(Tags.of("channel", channel != null ? channel.toLowerCase() : "unknown"))
            .register(registry)
            .increment();
    }
}
