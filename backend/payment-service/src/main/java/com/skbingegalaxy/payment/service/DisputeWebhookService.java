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
    private final PaymentService paymentService;

    /**
     * Monotonic dispute lifecycle rank (PAY-009). Provider events can arrive
     * duplicated and OUT OF ORDER; a terminal state (WON/LOST/ACCEPTED) must
     * never be reopened by a late created/under_review delivery, and one
     * terminal outcome must never overwrite another (won-after-lost would
     * silently flip financial truth — that needs a human).
     */
    private static int statusRank(String status) {
        if (status == null) return -1;
        return switch (status) {
            case "OPEN" -> 0;
            case "UNDER_REVIEW" -> 1;
            case "WON", "LOST", "ACCEPTED" -> 2;
            default -> -1;
        };
    }

    private static boolean isTerminal(String status) {
        return statusRank(status) >= 2;
    }

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

        // ── Monotonic ordering fence (PAY-009) ─────────────────────────────
        // Late/out-of-order deliveries must not move the dispute backwards,
        // and a second terminal outcome must never overwrite the first.
        String targetStatus = switch (event) {
            case "payment.dispute.created"      -> "OPEN";
            case "payment.dispute.under_review" -> "UNDER_REVIEW";
            case "payment.dispute.won"          -> "WON";
            case "payment.dispute.lost"         -> "LOST";
            case "payment.dispute.accepted"     -> "ACCEPTED";
            default -> null;
        };
        if (targetStatus == null) {
            log.debug("Unhandled dispute event subtype: {}", event);
            recordDedup(dedupKey, rawBody);
            return "unhandled:" + event;
        }
        String currentStatus = disputeRepository.findByGatewayDisputeId(dispute.getId())
            .map(PaymentDispute::getStatus).orElse(null);
        if (currentStatus != null) {
            if (isTerminal(currentStatus) && !currentStatus.equals(targetStatus)) {
                log.error("DISPUTE_ORDER_CONFLICT: dispute {} is terminal ({}) but received '{}' — refused; "
                    + "if the provider genuinely corrected the outcome, ops must reconcile manually.",
                    dispute.getId(), currentStatus, event);
                recordDedup(dedupKey, rawBody);
                return "ignored_out_of_order:" + currentStatus;
            }
            if (statusRank(targetStatus) < statusRank(currentStatus)) {
                log.info("Dispute {} ignoring backwards transition {} → {} (late delivery)",
                    dispute.getId(), currentStatus, targetStatus);
                recordDedup(dedupKey, rawBody);
                return "ignored_backwards:" + currentStatus;
            }
        }

        String result = switch (event) {
            case "payment.dispute.created"      -> handleDisputeCreated(payment, dispute, disputeAmount, currency, rawBody);
            case "payment.dispute.under_review" -> handleDisputeUnderReview(payment, dispute, disputeAmount, currency, rawBody);
            case "payment.dispute.won"          -> handleDisputeWon(payment, dispute, disputeAmount, currency, rawBody);
            case "payment.dispute.lost"         -> handleDisputeLost(payment, dispute, disputeAmount, currency, rawBody);
            case "payment.dispute.accepted"     -> handleDisputeAccepted(payment, dispute, disputeAmount, currency, rawBody);
            default -> "unhandled:" + event;
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
        // Freeze in DISPUTED only from an ordinary money state — a payment
        // that is already REFUNDED (or FAILED) must keep its ledger truth.
        if (prevStatus == PaymentStatus.SUCCESS || prevStatus == PaymentStatus.PARTIALLY_REFUNDED) {
            payment.setStatus(PaymentStatus.DISPUTED);
            paymentRepository.save(payment);
        } else {
            log.warn("Dispute {} opened for payment {} in state {} — dispute recorded, payment status untouched",
                dispute.getId(), payment.getTransactionId(), prevStatus);
        }

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
                                            BigDecimal amount, String currency, String rawBody) {
        log.info("Dispute under review: {} for booking {}", dispute.getId(), payment.getBookingRef());
        // Upsert (not update-only): under_review may be the FIRST event we see
        // when created was lost — the record must exist for the triage queue.
        upsertDispute(payment, dispute, "UNDER_REVIEW", amount, currency, rawBody);
        auditLogService.record("RAZORPAY_WEBHOOK", "DISPUTE_UNDER_REVIEW", "PAYMENT",
            payment.getTransactionId(), null, null, payment.getBingeId(),
            java.util.Map.of("disputeId", dispute.getId(), "bookingRef", payment.getBookingRef()));
        return "dispute_under_review";
    }

    private String handleDisputeWon(Payment payment, DisputeWebhookRequest.DisputeFields dispute,
                                    BigDecimal amount, String currency, String rawBody) {
        log.info("Dispute WON: payment={} booking={} — funds released by gateway",
            payment.getTransactionId(), payment.getBookingRef());

        // Restore from the settled-refund ledger, not a blind SUCCESS stamp —
        // and only when the payment is actually frozen in DISPUTED (PAY-009).
        paymentService.restoreAfterDisputeWon(payment, dispute.getId());

        upsertDispute(payment, dispute, "WON", amount, currency, rawBody);
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

        // Real chargeback ledger entry (PAY-009): Refund row + payment status
        // recomputed from the ledger + payment.refunded event so the booking's
        // collected amount and the customer timeline reflect the deduction.
        paymentService.applyChargeback(payment, dispute.getId(), amount,
            "Chargeback lost: dispute " + dispute.getId());

        upsertDispute(payment, dispute, "LOST", amount, currency, rawBody);
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

        paymentService.applyChargeback(payment, dispute.getId(), amount,
            "Chargeback accepted: dispute " + dispute.getId());

        upsertDispute(payment, dispute, "ACCEPTED", amount, currency, rawBody);
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
