package com.skbingegalaxy.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Processes Razorpay refund lifecycle webhooks — the authoritative settlement
 * signal for refunds the gateway accepted but had not yet processed when we
 * created them (rows in {@code RefundStatus.PROCESSING}).
 *
 * <pre>
 *   refund.processed → settle the row SUCCEEDED, recompute the parent payment
 *                      status, publish payment.refunded (customer email)
 *   refund.failed    → mark the row FAILED; it surfaces in the admin
 *                      failed-refund queue for retry
 * </pre>
 *
 * Signature verification happens in the controller (same HMAC-SHA256 webhook
 * secret as disputes). Deduplication uses the shared processed-webhook table.
 * A reconciliation poller ({@code PaymentReconciliationScheduler}) covers the
 * case where this webhook never arrives.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundWebhookService {

    private final PaymentService paymentService;
    private final WebhookDedupService webhookDedupService;
    private final ObjectMapper objectMapper;

    public String handleRefundEvent(String event, String rawBody) {
        JsonNode entity;
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            entity = root.path("payload").path("refund").path("entity");
        } catch (Exception e) {
            log.warn("Unparseable refund webhook body: {}", e.getMessage());
            return "unparseable";
        }
        String gatewayRefundId = entity.path("id").asText(null);
        if (gatewayRefundId == null || gatewayRefundId.isBlank()) {
            log.warn("Refund webhook {} missing refund entity id", event);
            return "skipped:no_refund_id";
        }
        // Our stable intent receipt (PAY-006): lets the settle find an intent
        // whose finalize step crashed before the gateway refund id was stored.
        String receipt = entity.path("receipt").asText(null);
        if (receipt == null || receipt.isBlank()) {
            receipt = entity.path("notes").path("internal_ref").asText(null);
        }

        String dedupKey = "refund:" + gatewayRefundId + ":" + event;
        if (webhookDedupService.isDuplicate(dedupKey)) {
            log.info("Duplicate refund webhook {} for {}", event, gatewayRefundId);
            return "duplicate";
        }

        String result = switch (event) {
            case "refund.processed" ->
                paymentService.settleRefundFromGateway(gatewayRefundId, receipt, "processed", "razorpay_webhook");
            case "refund.failed" ->
                paymentService.settleRefundFromGateway(gatewayRefundId, receipt, "failed", "razorpay_webhook");
            default -> "ignored:" + event; // refund.created / refund.speed_changed carry no settle signal
        };

        recordDedup(dedupKey, rawBody);
        return result;
    }

    private void recordDedup(String key, String rawBody) {
        try {
            webhookDedupService.recordNew(key, rawBody != null && rawBody.length() > 500
                ? rawBody.substring(0, 500) : rawBody);
        } catch (DataIntegrityViolationException ignored) {
            // Concurrent delivery won the race — fine.
        } catch (Exception e) {
            log.warn("Could not record refund dedup marker for {}: {}", key, e.getMessage());
        }
    }
}
