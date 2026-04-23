package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.payment.entity.ProcessedWebhookEvent;
import com.skbingegalaxy.payment.repository.ProcessedWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * Gateway-callback / webhook dedup.
 *
 * <p>Razorpay (and every major PSP) explicitly warn that webhooks can be
 * delivered duplicated, out of order, or replayed. The dedup key is a stable
 * identity tuple assigned by the gateway — for Razorpay we use
 * {@code orderId:paymentId:status} which uniquely identifies a settlement
 * outcome. The first recorder wins; subsequent duplicates short-circuit
 * before any side effects.
 *
 * <p>A separate {@link #recordNew} call uses a {@code REQUIRES_NEW}
 * transaction so the dedup insert commits independently of the main callback
 * transaction. That way, if the main transaction later rolls back, we don't
 * lose the dedup record and re-process the same event on redelivery; if the
 * dedup insert fails for any reason, the main flow can continue (at worst
 * we'll see another duplicate delivery).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDedupService {

    private static final String PROVIDER_RAZORPAY = "RAZORPAY";

    private final ProcessedWebhookEventRepository repository;

    @Value("${app.webhook.retention-days:30}")
    private int retentionDays;

    /** True if we've seen this event before. */
    @Transactional(readOnly = true)
    public boolean isDuplicate(String eventId) {
        if (eventId == null || eventId.isBlank()) return false;
        return repository.existsByEventIdAndProvider(eventId, PROVIDER_RAZORPAY);
    }

    /**
     * Record a fresh event. Uses {@code REQUIRES_NEW} so the dedup record
     * commits before the caller's outer transaction, guaranteeing that even
     * a crash between this call and the caller's commit leaves the event
     * marked as processed (the alternative — reprocessing on retry — is a
     * duplicate-charge hazard and is worse).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNew(String eventId, String payload) {
        if (eventId == null || eventId.isBlank()) return;
        try {
            ProcessedWebhookEvent row = ProcessedWebhookEvent.builder()
                .eventId(eventId)
                .provider(PROVIDER_RAZORPAY)
                .payloadHash(payload == null ? null : sha256(payload))
                .receivedAt(LocalDateTime.now())
                .build();
            repository.save(row);
        } catch (DataIntegrityViolationException dup) {
            // Race: two workers got the same delivery at the same time.
            // Whichever lost the insert race treats it as a duplicate and bails.
            log.info("Webhook event {} was concurrently recorded — treating as duplicate", eventId);
            throw dup; // caller handles it the same way as isDuplicate()==true
        }
    }

    /**
     * Derive a stable eventId from Razorpay callback fields. Using the
     * gateway-assigned identity (paymentId + orderId + final status) rather
     * than a local hash means replays from the gateway or manual support
     * reruns all collapse to the same key.
     */
    public String razorpayEventId(String gatewayOrderId, String gatewayPaymentId, String status) {
        // status participates in the key so a later capture callback for the
        // same authorization is NOT deduped away as a "duplicate" of the auth.
        return "rzp:" + nz(gatewayOrderId) + ":" + nz(gatewayPaymentId) + ":" + nz(status).toLowerCase();
    }

    /** Daily pruning of old dedup records beyond retention. */
    @Scheduled(
        fixedDelayString = "${app.webhook.cleanup-interval-ms:86400000}",
        initialDelayString = "${app.webhook.cleanup-initial-delay-ms:300000}"
    )
    @SchedulerLock(name = "webhookDedupCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purgeOld() {
        int removed = repository.deleteAllByReceivedAtBefore(LocalDateTime.now().minusDays(retentionDays));
        if (removed > 0) log.info("Purged {} old webhook dedup records (>{} days)", removed, retentionDays);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
