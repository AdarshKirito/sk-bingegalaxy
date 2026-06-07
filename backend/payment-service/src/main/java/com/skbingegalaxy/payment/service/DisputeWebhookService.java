package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.payment.dto.DisputeWebhookRequest;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.entity.PaymentDispute;
import com.skbingegalaxy.payment.repository.PaymentDisputeRepository;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Processes Razorpay dispute (chargeback) lifecycle webhooks.
 *
 * Dispute flow:
 *   payment.dispute.created   → mark payment DISPUTED, open dispute record, alert ops
 *   payment.dispute.under_review → update status, log evidence submission window
 *   payment.dispute.won       → revert payment to SUCCESS, close dispute record
 *   payment.dispute.lost      → mark payment REFUNDED (money already deducted by gateway),
 *                               create a synthetic Refund row for accounting
 *   payment.dispute.accepted  → same as lost — merchant voluntarily accepted the chargeback
 *
 * Key invariant: booking status is NEVER changed by dispute events. Cancellation is
 * an ops decision made after reviewing the outcome — not an automatic side-effect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeWebhookService {

    private static final BigDecimal PAISE_TO_INR = BigDecimal.valueOf(100);

    private final PaymentRepository paymentRepository;
    private final PaymentDisputeRepository disputeRepository;
    private final WebhookDedupService webhookDedupService;
    private final AuditLogService auditLogService;
    private final PaymentMetrics metrics;

    @Value("${app.razorpay.webhook-secret:}")
    private String razorpayWebhookSecret;

    /**
     * Validates the Razorpay webhook HMAC-SHA256 signature.
     * Razorpay signs the raw request body with the webhook secret (distinct from the
     * API key secret) using HMAC-SHA256 and sends it in X-Razorpay-Signature.
     */
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        if (razorpayWebhookSecret == null || razorpayWebhookSecret.isBlank()) {
            log.warn("RAZORPAY_WEBHOOK_SECRET not configured — dispute webhook signature cannot be verified");
            return false;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(razorpayWebhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computed = bytesToHex(hash);
            return java.security.MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Dispute webhook signature verification error", e);
            return false;
        }
    }

    /**
     * Entry point called by the controller after signature verification.
     * Returns a brief status string for logging; throws only on unrecoverable errors.
     */
    @Transactional
    public String handleDisputeEvent(DisputeWebhookRequest request, String rawBody) {
        String event = request.getEvent();
        if (event == null || !event.startsWith("payment.dispute.")) {
            log.debug("Ignoring non-dispute event: {}", event);
            return "ignored:" + event;
        }

        DisputeWebhookRequest.DisputeFields dispute = extractDispute(request);
        if (dispute == null || dispute.getId() == null) {
            log.warn("Dispute webhook missing dispute.id for event {}", event);
            return "skipped:no_dispute_id";
        }

        String dedupKey = "dispute:" + dispute.getId() + ":" + event;
        if (webhookDedupService.isDuplicate(dedupKey)) {
            metrics.webhookDuplicate();
            log.info("Duplicate dispute webhook {} for dispute {}", event, dispute.getId());
            return "duplicate";
        }

        String gatewayOrderId = extractOrderId(request);
        if (gatewayOrderId == null) {
            log.warn("Dispute webhook {} missing payment order_id", event);
            return "skipped:no_order_id";
        }

        Optional<Payment> paymentOpt = paymentRepository.findByGatewayOrderIdForUpdate(gatewayOrderId);
        if (paymentOpt.isEmpty()) {
            log.warn("Dispute webhook {} references unknown gateway order {}", event, gatewayOrderId);
            recordDedup(dedupKey, rawBody);
            return "unknown_payment";
        }

        Payment payment = paymentOpt.get();
        BigDecimal disputeAmount = dispute.getAmountPaise() != null
            ? BigDecimal.valueOf(dispute.getAmountPaise()).divide(PAISE_TO_INR, 2, RoundingMode.HALF_UP)
            : payment.getAmount();
        String currency = dispute.getCurrency() != null ? dispute.getCurrency() : "INR";

        String result = switch (event) {
            case "payment.dispute.created"      -> handleDisputeCreated(payment, dispute, disputeAmount, currency, rawBody);
            case "payment.dispute.under_review" -> handleDisputeUnderReview(payment, dispute, rawBody);
            case "payment.dispute.won"          -> handleDisputeWon(payment, dispute, disputeAmount, currency, rawBody);
            case "payment.dispute.lost"         -> handleDisputeLost(payment, dispute, disputeAmount, currency, rawBody);
            case "payment.dispute.accepted"     -> handleDisputeAccepted(payment, dispute, disputeAmount, currency, rawBody);
            default -> {
                log.debug("Unhandled dispute event subtype: {}", event);
                yield "unhandled:" + event;
            }
        };

        recordDedup(dedupKey, rawBody);
        return result;
    }

    // ── Event handlers ─────────────────────────────────────────────────────────

    private String handleDisputeCreated(Payment payment, DisputeWebhookRequest.DisputeFields dispute,
                                        BigDecimal amount, String currency, String rawBody) {
        log.warn("DISPUTE OPENED: payment={} booking={} amount={} reason={} respondBy={}",
            payment.getTransactionId(), payment.getBookingRef(), amount,
            dispute.getReasonCode(), dispute.getRespondByEpoch());

        PaymentStatus prevStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.DISPUTED);
        paymentRepository.save(payment);

        upsertDispute(payment, dispute, "OPEN", amount, currency, rawBody);

        metrics.disputeOpened();
        auditLogService.record("RAZORPAY_WEBHOOK", "DISPUTE_OPENED", "PAYMENT",
            payment.getTransactionId(), amount, currency, payment.getBingeId(),
            java.util.Map.of(
                "disputeId",   dispute.getId(),
                "bookingRef",  payment.getBookingRef(),
                "reasonCode",  dispute.getReasonCode() != null ? dispute.getReasonCode() : "",
                "prevStatus",  prevStatus.name(),
                "respondBy",   dispute.getRespondByEpoch() != null ? dispute.getRespondByEpoch().toString() : ""));

        return "dispute_opened";
    }

    private String handleDisputeUnderReview(Payment payment, DisputeWebhookRequest.DisputeFields dispute,
                                             String rawBody) {
        log.info("Dispute under review: {} for booking {}", dispute.getId(), payment.getBookingRef());
        upsertDisputeStatus(dispute.getId(), "UNDER_REVIEW", rawBody);
        auditLogService.record("RAZORPAY_WEBHOOK", "DISPUTE_UNDER_REVIEW", "PAYMENT",
            payment.getTransactionId(), null, null, payment.getBingeId(),
            java.util.Map.of("disputeId", dispute.getId(), "bookingRef", payment.getBookingRef()));
        return "dispute_under_review";
    }

    private String handleDisputeWon(Payment payment, DisputeWebhookRequest.DisputeFields dispute,
                                    BigDecimal amount, String currency, String rawBody) {
        log.info("Dispute WON: payment={} booking={} — funds released by gateway",
            payment.getTransactionId(), payment.getBookingRef());

        // Restore payment to SUCCESS; funds have been released by the gateway.
        payment.setStatus(PaymentStatus.SUCCESS);
        paymentRepository.save(payment);

        upsertDisputeStatus(dispute.getId(), "WON", rawBody);
        metrics.disputeWon();
        auditLogService.record("RAZORPAY_WEBHOOK", "DISPUTE_WON", "PAYMENT",
            payment.getTransactionId(), amount, currency, payment.getBingeId(),
            java.util.Map.of("disputeId", dispute.getId(), "bookingRef", payment.getBookingRef()));
        return "dispute_won";
    }

    private String handleDisputeLost(Payment payment, DisputeWebhookRequest.DisputeFields dispute,
                                     BigDecimal amount, String currency, String rawBody) {
        log.warn("Dispute LOST: payment={} booking={} amount={} — gateway has deducted funds",
            payment.getTransactionId(), payment.getBookingRef(), amount);

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setFailureReason("Chargeback lost: dispute " + dispute.getId());
        paymentRepository.save(payment);

        upsertDisputeStatus(dispute.getId(), "LOST", rawBody);
        metrics.disputeLost();
        auditLogService.record("RAZORPAY_WEBHOOK", "DISPUTE_LOST", "PAYMENT",
            payment.getTransactionId(), amount, currency, payment.getBingeId(),
            java.util.Map.of(
                "disputeId",  dispute.getId(),
                "bookingRef", payment.getBookingRef(),
                "note",       "Booking NOT auto-cancelled — ops team must review"));
        return "dispute_lost";
    }

    private String handleDisputeAccepted(Payment payment, DisputeWebhookRequest.DisputeFields dispute,
                                          BigDecimal amount, String currency, String rawBody) {
        log.warn("Dispute ACCEPTED (merchant conceded): payment={} booking={}",
            payment.getTransactionId(), payment.getBookingRef());

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setFailureReason("Chargeback accepted: dispute " + dispute.getId());
        paymentRepository.save(payment);

        upsertDisputeStatus(dispute.getId(), "ACCEPTED", rawBody);
        metrics.disputeLost();
        auditLogService.record("RAZORPAY_WEBHOOK", "DISPUTE_ACCEPTED", "PAYMENT",
            payment.getTransactionId(), amount, currency, payment.getBingeId(),
            java.util.Map.of("disputeId", dispute.getId(), "bookingRef", payment.getBookingRef()));
        return "dispute_accepted";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void upsertDispute(Payment payment, DisputeWebhookRequest.DisputeFields d,
                               String status, BigDecimal amount, String currency, String rawBody) {
        Optional<PaymentDispute> existingOpt = disputeRepository.findByGatewayDisputeId(d.getId());
        if (existingOpt.isPresent()) {
            PaymentDispute existing = existingOpt.get();
            existing.setStatus(status);
            existing.setRawPayload(rawBody);
            disputeRepository.save(existing);
            return;
        }
        // No try-catch around the insert: catching DataIntegrityViolationException within
        // the same @Transactional context would leave the JPA EntityManager in an invalid
        // state, causing any subsequent repository call to throw.
        // The dedup check at the start of handleDisputeEvent prevents concurrent identical
        // events from both reaching here. If an extremely rare concurrent insert race still
        // occurs, Razorpay retries the webhook on a non-2xx response, and the retry will
        // find the record via the existingOpt.isPresent() branch above.
        PaymentDispute entity = PaymentDispute.builder()
            .payment(payment)
            .gatewayDisputeId(d.getId())
            .bingeId(payment.getBingeId())
            .bookingRef(payment.getBookingRef())
            .amount(amount)
            .currency(currency)
            .status(status)
            .reasonCode(d.getReasonCode())
            .respondBy(d.getRespondByEpoch() != null
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(d.getRespondByEpoch()), ZoneOffset.UTC)
                : null)
            .gatewayCreatedAt(d.getCreatedAtEpoch() != null
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(d.getCreatedAtEpoch()), ZoneOffset.UTC)
                : null)
            .rawPayload(rawBody)
            .build();
        disputeRepository.save(entity);
    }

    private void upsertDisputeStatus(String gatewayDisputeId, String status, String rawBody) {
        disputeRepository.findByGatewayDisputeId(gatewayDisputeId).ifPresent(d -> {
            d.setStatus(status);
            d.setRawPayload(rawBody);
            disputeRepository.save(d);
        });
    }

    private void recordDedup(String key, String rawBody) {
        try {
            webhookDedupService.recordNew(key, rawBody != null && rawBody.length() > 500
                ? rawBody.substring(0, 500) : rawBody);
        } catch (DataIntegrityViolationException ignored) {
            // Concurrent delivery won the race — fine.
        } catch (Exception e) {
            log.warn("Could not record dispute dedup marker for {}: {}", key, e.getMessage());
        }
    }

    private static DisputeWebhookRequest.DisputeFields extractDispute(DisputeWebhookRequest req) {
        if (req.getPayload() == null || req.getPayload().getDispute() == null) return null;
        return req.getPayload().getDispute().getEntity();
    }

    private static String extractOrderId(DisputeWebhookRequest req) {
        if (req.getPayload() == null || req.getPayload().getPayment() == null) return null;
        DisputeWebhookRequest.PaymentFields pf = req.getPayload().getPayment().getEntity();
        return pf != null ? pf.getOrderId() : null;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
