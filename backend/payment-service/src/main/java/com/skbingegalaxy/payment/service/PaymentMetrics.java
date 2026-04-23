package com.skbingegalaxy.payment.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Production-grade counters for the operational signals a payments team must
 * watch (per the big-company checklist):
 *
 * <ul>
 *   <li>{@code skbg_payment_duplicate_attempts_total} — Guard 1/Guard 2 hits on
 *       {@code POST /initiate}; high rate ⇒ client bug, network flap, or card-testing.</li>
 *   <li>{@code skbg_payment_idempotency_hits_total{result}} — hit/mismatch/stored
 *       breakdown of the Idempotency-Key path.</li>
 *   <li>{@code skbg_payment_webhook_total{outcome}} — fresh / duplicate /
 *       unsigned / invalid-signature / stale webhook counts.</li>
 *   <li>{@code skbg_payment_signature_failures_total} — specific counter for
 *       HMAC failures, surfaced for fraud/abuse alerting.</li>
 *   <li>{@code skbg_payment_refund_total{outcome}} — issued / rejected /
 *       auto-refund-late-capture.</li>
 *   <li>{@code skbg_saga_compensation_total} — booking compensations fired.</li>
 *   <li>{@code skbg_card_testing_suspected_total} — burst of low-amount
 *       failures from the same user/IP in a short window (wired by the
 *       PrometheusRule in {@code k8s/monitoring.yml}).</li>
 * </ul>
 *
 * <p>All counters are registered lazily via {@link MeterRegistry#counter} so
 * the service starts fine in test profiles that disable Prometheus.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Idempotency ────────────────────────────────────────────────────────
    public void idempotencyHit()       { counter("skbg_payment_idempotency_hits_total", "result", "hit").increment(); }
    public void idempotencyMismatch()  { counter("skbg_payment_idempotency_hits_total", "result", "mismatch").increment(); }
    public void idempotencyStored()    { counter("skbg_payment_idempotency_hits_total", "result", "stored").increment(); }

    // ── Duplicate payment attempts ─────────────────────────────────────────
    public void duplicateInitiated()   { counter("skbg_payment_duplicate_attempts_total", "kind", "initiated").increment(); }
    public void duplicateSuccessful()  { counter("skbg_payment_duplicate_attempts_total", "kind", "already_succeeded").increment(); }

    // ── Webhook / callback outcomes ────────────────────────────────────────
    public void webhookFresh()            { counter("skbg_payment_webhook_total", "outcome", "fresh").increment(); }
    public void webhookDuplicate()        { counter("skbg_payment_webhook_total", "outcome", "duplicate").increment(); }
    public void webhookStale()            { counter("skbg_payment_webhook_total", "outcome", "stale").increment(); }
    public void webhookUnsigned()         { counter("skbg_payment_webhook_total", "outcome", "unsigned").increment(); }
    public void webhookInvalidSignature() { counter("skbg_payment_webhook_total", "outcome", "invalid_signature").increment(); }
    public void signatureFailure()        { counter("skbg_payment_signature_failures_total").increment(); }

    // ── Refund / lifecycle ─────────────────────────────────────────────────
    public void refundIssued()    { counter("skbg_payment_refund_total", "outcome", "issued").increment(); }
    public void refundRejected()  { counter("skbg_payment_refund_total", "outcome", "rejected").increment(); }
    public void refundAutoLate()  { counter("skbg_payment_refund_total", "outcome", "auto_late_capture").increment(); }

    // ── Saga compensation ──────────────────────────────────────────────────
    public void sagaCompensated() { counter("skbg_saga_compensation_total").increment(); }

    // ── Fraud signals ──────────────────────────────────────────────────────
    public void cardTestingSuspected() { counter("skbg_card_testing_suspected_total").increment(); }

    // ── helpers ────────────────────────────────────────────────────────────
    private Counter counter(String name) {
        return registry.counter(name);
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return Counter.builder(name).tags(Tags.of(tagKey, tagValue)).register(registry);
    }
}
