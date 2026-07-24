package com.skbingegalaxy.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.payment.service.DisputeAdminService;
import com.skbingegalaxy.payment.service.DisputeWebhookService;
import com.skbingegalaxy.payment.service.PaymentBingeScopeService;
import com.skbingegalaxy.payment.service.ConnectedAccountService;
import com.skbingegalaxy.payment.entity.PaymentConnectedAccount;
import com.skbingegalaxy.payment.service.IdempotencyService;
import com.skbingegalaxy.payment.dto.*;
import com.skbingegalaxy.payment.service.PaymentService;
import com.skbingegalaxy.payment.service.WebhookDedupService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Validated
@Slf4j
public class PaymentController {

    private final PaymentBingeScopeService scopeService;
    private final PaymentService paymentService;
    private final DisputeWebhookService disputeWebhookService;
    private final com.skbingegalaxy.payment.service.RefundWebhookService refundWebhookService;
    private final DisputeAdminService disputeAdminService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final ConnectedAccountService connectedAccountService;
    private final com.skbingegalaxy.payment.client.StripeGatewayClient stripeGatewayClient;
    private final WebhookDedupService webhookDedupService;

    @ModelAttribute
    void validateBingeScope(
            HttpServletRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        String uri = request.getRequestURI();
        // Gateway-facing webhook endpoints are called directly by Razorpay —
        // they have no binge context and must validate only their own HMAC signature.
        if (uri.endsWith("/callback") || uri.contains("/webhooks/")) {
            return;
        }
        if (uri.contains("/admin/")) {
            var binge = scopeService.requireManagedBinge(userId, role, "managing payments");
            // V71 module matrix — these payment modules belong to this
            // service's URL space, so their 403 is enforced here. Only the
            // failed-refund QUEUE + retry are the FAILED_REFUNDS module;
            // ordinary refund reads/issuance (booking payment tab) stay open.
            if (uri.contains("/admin/disputes")) {
                scopeService.requireModuleAllowed(binge, role, "DISPUTES");
            } else if (uri.contains("/admin/refunds/failed")
                    || (uri.contains("/admin/refunds/") && uri.endsWith("/retry"))) {
                scopeService.requireModuleAllowed(binge, role, "FAILED_REFUNDS");
            }
            return;
        }
        scopeService.requireSelectedBinge("accessing payments");
    }

    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<PaymentDto>> initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", required = false, defaultValue = "CUSTOMER") String userRole,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Name", required = false) String userName,
            @RequestHeader(value = "X-User-Phone", required = false) String userPhone,
            @RequestHeader(value = "X-User-Phone-Country-Code", required = false) String userPhoneCountryCode,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentDto payment = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/payments/initiate", userId, request, PaymentDto.class,
            () -> paymentService.initiatePayment(request, userId, userRole, userEmail, userName,
                userPhone, userPhoneCountryCode));
        return ResponseEntity.ok(ApiResponse.ok("Payment initiated", payment));
    }

    /**
     * Payment rails offered for a booking, derived from the VENUE's country so a
     * customer abroad pays on the venue's local rails. The checkout page calls
     * this instead of rendering a hardcoded method list; {@code /initiate}
     * re-enforces the same resolution server-side.
     */
    @GetMapping("/methods/{bookingRef}")
    public ResponseEntity<ApiResponse<PaymentMethodOptionsDto>> getPaymentMethods(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String userRole) {
        return ResponseEntity.ok(ApiResponse.ok(
            paymentService.getPaymentMethodOptions(bookingRef, userId, userRole)));
    }

    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<PaymentDto>> handleCallback(
            @Valid @RequestBody PaymentCallbackRequest request) {
        PaymentDto payment = paymentService.handleCallback(request);
        return ResponseEntity.ok(ApiResponse.ok("Payment callback processed", payment));
    }

    @PostMapping("/admin/simulate/{transactionId}")
    public ResponseEntity<ApiResponse<PaymentDto>> simulatePayment(
            @PathVariable String transactionId,
            @RequestHeader("X-User-Role") String userRole) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can simulate payments", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        PaymentDto payment = paymentService.simulatePayment(transactionId);
        return ResponseEntity.ok(ApiResponse.ok("Payment simulated", payment));
    }

    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<ApiResponse<PaymentDto>> getByTransactionId(
            @PathVariable String transactionId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String userRole) {
        PaymentDto payment = paymentService.getPaymentByTransactionId(transactionId, userId, userRole);
        return ResponseEntity.ok(ApiResponse.ok("Payment details", payment));
    }

    @GetMapping("/booking/{bookingRef}")
    public ResponseEntity<ApiResponse<List<PaymentDto>>> getByBookingRef(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String userRole) {
        List<PaymentDto> payments = paymentService.getPaymentsByBookingRef(bookingRef, userId, userRole);
        return ResponseEntity.ok(ApiResponse.ok("Payments for booking", payments));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<PaymentDto>>> getMyPayments(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size > 100) size = 100;
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        var result = paymentService.getCustomerPaymentsPaginated(userId, pageable);
        return ResponseEntity.ok(ApiResponse.ok("Your payments", result));
    }

    /**
     * Lifetime aggregates for the customer (FE-001): the dashboard and
     * account-center "total spent / transactions" tiles must come from a
     * server-side SUM/COUNT over ALL rows — deriving them from one 20-row
     * page silently understated every customer with >20 payments.
     */
    @GetMapping("/my/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyPaymentsSummary(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok("Your payment summary",
            paymentService.getCustomerPaymentSummary(userId)));
    }

    @PostMapping("/admin/refund")
    public ResponseEntity<ApiResponse<RefundDto>> initiateRefund(
            @Valid @RequestBody RefundRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Email") String adminEmail,
            @RequestHeader("X-User-Role") String userRole,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can initiate refunds", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        RefundDto refund = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/payments/admin/refund", adminId, request, RefundDto.class,
            () -> paymentService.initiateRefund(request, adminEmail));
        // Fail-closed refunds (PAY-002) persist a FAILED attempt instead of
        // throwing, so the row lands in the failed-refund queue — but the admin
        // must not see a success response for money that never moved.
        if (refund.getRefundStatus() == com.skbingegalaxy.payment.entity.RefundStatus.FAILED) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error("Gateway refund failed — the attempt was recorded in the "
                    + "Failed Refunds queue for retry. "
                    + (refund.getFailureReason() != null ? refund.getFailureReason() : "")));
        }
        String message = switch (refund.getRefundStatus()) {
            case PROCESSING -> "Refund accepted by the gateway — awaiting settlement confirmation";
            // PAY-006 ambiguous outcome: the intent is durable and reconciliation
            // will confirm with the provider — the admin must not re-submit.
            case INITIATED -> "Refund submitted — provider confirmation pending (do not retry; "
                + "reconciliation will settle it automatically)";
            default -> "Refund initiated";
        };
        return ResponseEntity.ok(ApiResponse.ok(message, refund));
    }

    @GetMapping("/admin/refunds/{paymentId}")
    public ResponseEntity<ApiResponse<List<RefundDto>>> getRefunds(
            @PathVariable Long paymentId) {
        List<RefundDto> refunds = paymentService.getRefundsForPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.ok("Refunds for payment", refunds));
    }

    /**
     * Customer-facing refund timeline for a booking. Returns refunds in any
     * lifecycle state (CALCULATED → INITIATED → PROCESSING → SUCCEEDED/FAILED)
     * so the UI can render a status timeline. Customers only see refunds on
     * their own payments; admins see the binge-scoped set (SEC-011).
     */
    @GetMapping("/booking/{bookingRef}/refunds")
    public ResponseEntity<ApiResponse<List<RefundDto>>> getRefundsForBooking(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", required = false, defaultValue = "CUSTOMER") String userRole) {
        return ResponseEntity.ok(ApiResponse.ok(
            "Refund timeline for booking",
            paymentService.getRefundsForBooking(bookingRef, userId, userRole)));
    }

    /**
     * Admin failed-refund queue. Surfaces refunds whose own per-attempt
     * lifecycle ended in FAILED so ops can triage / retry.
     */
    @GetMapping("/admin/refunds/failed")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<RefundDto>>> getFailedRefunds(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-User-Role") String userRole) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can view the failed-refund queue",
                org.springframework.http.HttpStatus.FORBIDDEN);
        }
        if (size > 100) size = 100;
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.ok(
            "Failed refunds",
            paymentService.getFailedRefunds(pageable)));
    }

    /**
     * Retry a FAILED refund. Marks the original as SUPERSEDED and creates a new
     * INITIATED row that today (synchronous gateway path) settles to SUCCEEDED.
     */
    @PostMapping("/admin/refunds/{refundId}/retry")
    public ResponseEntity<ApiResponse<RefundDto>> retryFailedRefund(
            @PathVariable Long refundId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Email") String adminEmail,
            @RequestHeader("X-User-Role") String userRole,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can retry refunds", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        RefundDto refund = idempotencyService.execute(
            idempotencyKey, "POST",
            "/api/v1/payments/admin/refunds/" + refundId + "/retry",
            adminId, java.util.Map.of("refundId", refundId), RefundDto.class,
            () -> paymentService.retryFailedRefund(refundId, adminEmail));
        return ResponseEntity.ok(ApiResponse.ok("Refund retry issued", refund));
    }

    /**
     * Cancel an INITIATED payment before it reaches the gateway.
     * Only the payment owner can cancel their own payment.
     */
    @PostMapping("/cancel/{transactionId}")
    public ResponseEntity<ApiResponse<PaymentDto>> cancelPayment(
            @PathVariable String transactionId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentDto payment = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/payments/cancel/" + transactionId, userId,
            Map.of("transactionId", transactionId), PaymentDto.class,
            () -> paymentService.cancelPayment(transactionId, userId));
        return ResponseEntity.ok(ApiResponse.ok("Payment cancelled", payment));
    }

    /**
     * Admin dashboard statistics: revenue, refund totals, counts by status.
     */
    @GetMapping("/admin/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPaymentStats() {
        return ResponseEntity.ok(ApiResponse.ok("Payment statistics", paymentService.getPaymentStats()));
    }

    // ── Dispute / chargeback admin ────────────────────────────────────────────

    /**
     * List open disputes for the selected binge, sorted by respond-by deadline (most urgent first).
     * Ops must respond to Razorpay within 48–72 h — this is the primary triage queue.
     *
     * RED   = minutesUntilDeadline < 1440  (< 24 h)
     * AMBER = minutesUntilDeadline < 2880  (< 48 h)
     * GREEN = minutesUntilDeadline >= 2880 (> 48 h)
     */
    @GetMapping("/admin/disputes")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<PaymentDisputeDto>>> getOpenDisputes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-User-Role") String userRole) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can view disputes", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        if (size > 100) size = 100;
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.ok("Open disputes", disputeAdminService.getOpenDisputes(pageable)));
    }

    /**
     * List all disputes (including resolved) for the selected binge.
     * Use this for historical audit and chargeback win/loss rate reporting.
     */
    @GetMapping("/admin/disputes/all")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<PaymentDisputeDto>>> getAllDisputes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-User-Role") String userRole) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can view disputes", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        if (size > 100) size = 100;
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.ok("All disputes", disputeAdminService.getAllDisputes(pageable)));
    }

    /**
     * Count of open disputes for the admin dashboard badge.
     * Returns { "openDisputes": N } — non-zero triggers the red alert badge.
     */
    @GetMapping("/admin/disputes/count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> countOpenDisputes(
            @RequestHeader("X-User-Role") String userRole) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can view disputes", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return ResponseEntity.ok(ApiResponse.ok(
            "Open dispute count",
            Map.of("openDisputes", disputeAdminService.countOpenDisputes())));
    }

    /**
     * Add ops notes to a dispute record.
     * Notes are timestamped and cumulative — used to document evidence gathered
     * before submitting the dispute response to Razorpay.
     *
     * @param disputeId  internal ID of the PaymentDispute row
     * @param body       { "notes": "..." } — appended to existing notes with timestamp
     */
    @PatchMapping("/admin/disputes/{disputeId}/notes")
    public ResponseEntity<ApiResponse<PaymentDisputeDto>> updateDisputeNotes(
            @PathVariable Long disputeId,
            @RequestBody Map<String, String> body,
            @RequestHeader("X-User-Email") String adminEmail,
            @RequestHeader("X-User-Role") String userRole) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can update dispute notes", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        String notes = body.get("notes");
        if (notes == null || notes.isBlank()) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "notes field is required and must not be blank", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        return ResponseEntity.ok(ApiResponse.ok(
            "Dispute notes updated",
            disputeAdminService.updateNotes(disputeId, notes, adminEmail)));
    }

    /**
     * Razorpay dispute/chargeback webhook receiver.
     *
     * Called directly by Razorpay (not through the authenticated gateway path).
     * Security: validated by HMAC-SHA256 over the raw request body using the
     * webhook secret configured in RAZORPAY_WEBHOOK_SECRET (distinct from the
     * API key secret). Reject unsigned requests with 403.
     *
     * Register this URL in the Razorpay dashboard under:
     *   Settings → Webhooks → Add new webhook
     *   URL: https://<your-domain>/api/v1/payments/webhooks/razorpay
     *   Events: payment.dispute.created, payment.dispute.under_review,
     *           payment.dispute.won, payment.dispute.lost, payment.dispute.accepted,
     *           refund.processed, refund.failed
     */
    // ════════════════════════════════════════════════════════════════════
    // Stripe Connect — venue payment onboarding (admin)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Start or resume Stripe onboarding for the selected venue and return a
     * single-use hosted KYC link.
     *
     * <p>The venue's country comes from the authoritative internal binge snapshot,
     * never from the request body: it fixes the connected account's settlement
     * currency and available payment rails and cannot be changed afterwards, so a
     * client-supplied value could permanently mis-domicile the account.
     */
    @PostMapping("/admin/connect/onboard")
    public ResponseEntity<ApiResponse<ConnectedAccountService.OnboardingLink>> startStripeOnboarding(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        BookingBingeDto binge = scopeService.requireManagedBinge(userId, role, "connecting payments");
        // Email is left to Stripe's own onboarding form rather than guessed here —
        // the account holder is the legal entity being KYC'd, not necessarily the
        // admin clicking the button.
        return ResponseEntity.ok(ApiResponse.ok("Onboarding link created",
            connectedAccountService.startOnboarding(binge.getId(), binge.getCountry(), null)));
    }

    /** Current Stripe capability state for the selected venue (refreshed from Stripe). */
    @GetMapping("/admin/connect/status")
    public ResponseEntity<ApiResponse<PaymentConnectedAccount>> stripeConnectStatus(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        BookingBingeDto binge = scopeService.requireManagedBinge(userId, role, "viewing payment connection");
        return ResponseEntity.ok(ApiResponse.ok(connectedAccountService.refreshStatus(binge.getId())));
    }

    /**
     * Stripe webhook. Verified by HMAC-SHA256 over the RAW body per the
     * {@code Stripe-Signature} header — the body must not be re-serialised, since
     * any whitespace or key-order change invalidates the signature.
     *
     * Register at: Developers → Webhooks → Add endpoint
     *   URL: https://&lt;your-domain&gt;/api/v1/payments/webhooks/stripe
     *   Events: account.updated, payment_intent.succeeded, payment_intent.payment_failed,
     *           charge.refunded, refund.updated
     */
    @PostMapping("/webhooks/stripe")
    public ResponseEntity<Map<String, String>> handleStripeWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

        if (!stripeGatewayClient.verifyWebhookSignature(rawBody, signature, 300L)) {
            log.warn("Rejected Stripe webhook: invalid or missing signature");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Invalid webhook signature"));
        }

        final com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Failed to parse Stripe webhook body: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Unparseable webhook body"));
        }

        String type = root.path("type").asText("");
        var object = root.path("data").path("object");

        if ("account.updated".equals(type)) {
            // Capability changes decide whether a venue may be offered at checkout.
            connectedAccountService.applyWebhookState(
                object.path("id").asText(null),
                object.path("charges_enabled").asBoolean(false),
                object.path("payouts_enabled").asBoolean(false),
                object.path("details_submitted").asBoolean(false));
            return ResponseEntity.ok(Map.of("status", "account-updated"));
        }

        // Payment outcome. This is the AUTHORITATIVE settlement signal for Stripe —
        // its browser redirect carries no signature, so a customer closing the tab
        // mid-redirect must not leave a paid booking stuck INITIATED.
        if ("payment_intent.succeeded".equals(type) || "payment_intent.payment_failed".equals(type)) {
            String intentId = object.path("id").asText(null);
            boolean succeeded = "payment_intent.succeeded".equals(type);
            String failureReason = object.path("last_payment_error").path("message").asText(null);
            paymentService.settleStripeIntent(intentId, succeeded, failureReason);
            return ResponseEntity.ok(Map.of("status", succeeded ? "settled" : "failed"));
        }

        // Refund settlement. Stripe refunds can settle asynchronously (bank rails),
        // so the immediate API response may say "pending"; this is the authoritative
        // confirmation for those, mirroring Razorpay's refund.processed/failed.
        // A charge.refunded event carries the charge with its list of refunds.
        if ("charge.refunded".equals(type) || "refund.updated".equals(type)) {
            String result = settleStripeRefunds(type, object);
            return ResponseEntity.ok(Map.of("status", result));
        }

        log.debug("Unhandled Stripe webhook type {}", type);
        return ResponseEntity.ok(Map.of("status", "ignored"));
    }

    /**
     * Settle the refunds carried by a Stripe {@code charge.refunded} (charge object
     * with a {@code refunds.data[]}) or {@code refund.updated} (a single refund
     * object) event, reusing the same gateway-agnostic settle path as Razorpay.
     */
    private String settleStripeRefunds(String type, com.fasterxml.jackson.databind.JsonNode object) {
        java.util.List<com.fasterxml.jackson.databind.JsonNode> refunds = new java.util.ArrayList<>();
        if ("refund.updated".equals(type)) {
            refunds.add(object);
        } else {
            object.path("refunds").path("data").forEach(refunds::add);
        }
        int settled = 0;
        for (var refund : refunds) {
            String gatewayRefundId = refund.path("id").asText(null);
            if (gatewayRefundId == null || gatewayRefundId.isBlank()) continue;
            // internal_ref is the stable receipt we stamped at creation — lets settle
            // find an intent whose finalize crashed before the refund id was stored.
            String receipt = refund.path("metadata").path("internal_ref").asText(null);
            String status = refund.path("status").asText("");
            // Stripe: succeeded | pending | failed | canceled. Only terminal states
            // settle; pending waits for the next event.
            String normalised = switch (status) {
                case "succeeded" -> "processed";
                case "failed", "canceled" -> "failed";
                default -> null;
            };
            if (normalised == null) continue;

            String dedupKey = "stripe-refund:" + gatewayRefundId + ":" + normalised;
            if (webhookDedupService.isDuplicate(dedupKey)) continue;
            paymentService.settleRefundFromGateway(gatewayRefundId, receipt, normalised, "stripe_webhook");
            try {
                webhookDedupService.recordNew(dedupKey, "stripe refund " + gatewayRefundId);
            } catch (Exception ignored) {
                // Concurrent delivery won the race — the settle above is idempotent.
            }
            settled++;
        }
        return settled > 0 ? "settled:" + settled : "ignored";
    }

    @PostMapping("/webhooks/razorpay")
    public ResponseEntity<Map<String, String>> handleRazorpayWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        if (!disputeWebhookService.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Rejected Razorpay webhook: invalid or missing signature");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Invalid webhook signature"));
        }

        String event;
        try {
            event = objectMapper.readTree(rawBody).path("event").asText("");
        } catch (Exception e) {
            log.warn("Failed to parse Razorpay webhook body: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Unparseable webhook body"));
        }

        // Refund settlement events — authoritative confirmation for refunds the
        // gateway accepted asynchronously (RefundStatus.PROCESSING rows).
        if (event.startsWith("refund.")) {
            String result = refundWebhookService.handleRefundEvent(event, rawBody);
            log.info("Razorpay refund webhook processed: event={} result={}", event, result);
            return ResponseEntity.ok(Map.of("status", result));
        }

        DisputeWebhookRequest request;
        try {
            request = objectMapper.readValue(rawBody, DisputeWebhookRequest.class);
        } catch (Exception e) {
            log.warn("Failed to parse Razorpay dispute webhook body: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Unparseable webhook body"));
        }

        String result = disputeWebhookService.handleDisputeEvent(request, rawBody);
        log.info("Razorpay dispute webhook processed: event={} result={}", request.getEvent(), result);
        return ResponseEntity.ok(Map.of("status", result));
    }

    /**
     * Record a cash payment collected directly by admin.
     * Use this when a booking was paid by cash and no digital payment record exists.
     */
    @PostMapping("/admin/record-cash")
    public ResponseEntity<ApiResponse<PaymentDto>> recordCashPayment(
            @Valid @RequestBody RecordCashPaymentRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Email") String adminEmail,
            @RequestHeader("X-User-Role") String userRole,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can record cash payments", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        PaymentDto payment = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/payments/admin/record-cash", adminId, request, PaymentDto.class,
            () -> paymentService.recordCashPayment(request, adminEmail));
        return ResponseEntity.ok(ApiResponse.ok("Cash payment recorded", payment));
    }

    /**
     * Record an additional payment for a booking with any payment method.
     * Used for split payments, method changes, or collecting remaining balances.
     */
    @PostMapping("/admin/add-payment")
    public ResponseEntity<ApiResponse<PaymentDto>> addPayment(
            @Valid @RequestBody AddPaymentRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Email") String adminEmail,
            @RequestHeader("X-User-Role") String userRole,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can add payments", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        PaymentDto payment = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/payments/admin/add-payment", adminId, request, PaymentDto.class,
            () -> paymentService.addPayment(request, adminEmail));
        return ResponseEntity.ok(ApiResponse.ok("Payment recorded", payment));
    }
}