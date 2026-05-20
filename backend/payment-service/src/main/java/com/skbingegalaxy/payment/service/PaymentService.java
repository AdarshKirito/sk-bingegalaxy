package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.PaymentEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.payment.dto.*;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.entity.Refund;
import com.skbingegalaxy.payment.event.PaymentKafkaEvent;
import com.skbingegalaxy.payment.client.RazorpayGatewayClient;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import com.skbingegalaxy.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    /** Statuses that count as a completed refund for amount-calculation purposes. */
    private static final List<PaymentStatus> REFUNDED_STATUSES =
            List.of(PaymentStatus.REFUNDED);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RazorpayGatewayClient razorpayGatewayClient;
    private final com.skbingegalaxy.payment.client.BookingAmountClient bookingAmountClient;
    private final com.skbingegalaxy.payment.repository.PaymentStatusHistoryRepository statusHistoryRepository;
    private final WebhookDedupService webhookDedupService;
    private final AuditLogService auditLogService;
    private final PaymentMetrics metrics;
    private final AdminApprovalService approvalService;

    /** Self-reference for calling @Transactional methods from within the same bean. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private PaymentService self;

    @Value("${app.razorpay.key-secret}")
    private String razorpayKeySecret;

    @Value("${app.razorpay.key-id:}")
    private String razorpayKeyId;

    @Value("${app.payment.simulation-enabled:false}")
    private boolean paymentSimulationEnabled;

    /**
     * Above this amount (in payment currency, treated as a flat number),
     * a refund retry must go through the maker-checker workflow before it
     * actually moves money. Default 5,000 INR — overridable per environment.
     */
    @Value("${app.refund.retry-approval-threshold:5000}")
    private java.math.BigDecimal refundRetryApprovalThreshold;

    @Value("${app.payment.dedup-window-seconds:30}")
    private int dedupWindowSeconds;

    @jakarta.annotation.PostConstruct
    void validateConfig() {
        if (!paymentSimulationEnabled && (razorpayKeySecret == null || razorpayKeySecret.isBlank())) {
            throw new IllegalStateException(
                "RAZORPAY_KEY_SECRET must be set when payment simulation is disabled");
        }
        if (paymentSimulationEnabled && razorpayKeyId != null && !razorpayKeyId.isBlank()
                && !razorpayKeyId.startsWith("rzp_test_")) {
            // Live Razorpay keys alongside simulation = dangerous misconfiguration
            throw new IllegalStateException(
                "FATAL: Payment simulation is ENABLED alongside live Razorpay keys (key_id="
                + razorpayKeyId.substring(0, Math.min(12, razorpayKeyId.length())) + "...). "
                + "Set PAYMENT_SIMULATION_ENABLED=false for production or remove the Razorpay keys.");
        }
    }

    /**
     * Non-transactional entry point: creates the Razorpay gateway order
     * BEFORE acquiring the advisory lock so the DB connection is not
     * held throughout a potentially slow HTTP call (3-10s).
     */
    /** Backward-compat overload (callers without phone). */
    public PaymentDto initiatePayment(InitiatePaymentRequest request, Long customerId, String customerEmail, String customerName) {
        return initiatePayment(request, customerId, customerEmail, customerName, null, null);
    }

    public PaymentDto initiatePayment(InitiatePaymentRequest request, Long customerId, String customerEmail, String customerName,
                                      String customerPhone, String customerPhoneCountryCode) {
        log.info("Initiating payment for booking: {}, amount: {}", request.getBookingRef(), request.getAmount());

        // Create gateway order OUTSIDE the transaction - no DB locks held during the HTTP call
        String gatewayOrderId;
        if (!paymentSimulationEnabled && razorpayKeyId != null && !razorpayKeyId.isBlank()) {
            gatewayOrderId = razorpayGatewayClient.createOrder(
                request.getAmount(),
                request.getCurrency() != null ? request.getCurrency() : "INR",
                request.getBookingRef());
        } else {
            gatewayOrderId = "ORD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        }

        // Transactional section: acquire lock, check guards, persist payment
        return self.saveInitiatedPayment(request, customerId, customerEmail, customerName,
                customerPhone, customerPhoneCountryCode, gatewayOrderId);
    }

    @Transactional
    public PaymentDto saveInitiatedPayment(InitiatePaymentRequest request, Long customerId,
                                           String customerEmail, String customerName,
                                           String customerPhone, String customerPhoneCountryCode,
                                           String gatewayOrderId) {
        // Serialise concurrent initiations for the same booking to prevent duplicate INITIATED payments
        paymentRepository.acquirePaymentLock(request.getBookingRef().hashCode());

        // Guard 0: Fetch booking snapshot FIRST so terminally-closed bookings (CANCELLED,
        // NO_SHOW, EXPIRED) are rejected even if an INITIATED payment already exists from
        // before the cancellation. Fail-closed on unreachable booking-service.
        var snapshot = bookingAmountClient.fetchSnapshot(request.getBookingRef());
        if (snapshot == null) {
            throw new BusinessException(
                "Unable to verify booking balance — booking-service unavailable. Please retry.",
                HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (snapshot.status() != null) {
            String st = snapshot.status();
            if ("CANCELLED".equals(st) || "NO_SHOW".equals(st) || "EXPIRED".equals(st)) {
                throw new BusinessException(
                    "This booking is " + st.toLowerCase() + " and can no longer be paid. Please create a new booking.",
                    HttpStatus.CONFLICT);
            }
        }

        // Guard 1: payment already succeeded for this booking — reject duplicate attempt
        if (!findSuccessfulPaymentsForCurrentBinge(request.getBookingRef()).isEmpty()) {
            metrics.duplicateSuccessful();
            throw new BusinessException("Payment already completed for booking " + request.getBookingRef(), HttpStatus.CONFLICT);
        }

        // Guard 2 (idempotency): an INITIATED payment already exists — return it
        // instead of creating a duplicate (handles network-retry / double-click scenarios)
        var existing = findExistingInitiatedPaymentForCurrentBinge(request.getBookingRef());
        if (existing.isPresent()) {
            metrics.duplicateInitiated();
            log.info("Returning existing INITIATED payment {} for booking {}",
                    existing.get().getTransactionId(), request.getBookingRef());
            return toPaymentDtoWithRefunds(existing.get());
        }
        // Guard 3: Validate amount against booking's remaining balance (prevent client-side tampering)
        BigDecimal remainingBalance = snapshot.remainingBalance();
        if (remainingBalance == null) {
            throw new BusinessException(
                "Unable to verify booking balance — booking-service unavailable. Please retry.",
                HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (request.getAmount().compareTo(remainingBalance) > 0) {
            throw new BusinessException(
                String.format("Payment amount ₹%.2f exceeds remaining booking balance ₹%.2f",
                    request.getAmount(), remainingBalance),
                HttpStatus.BAD_REQUEST);
        }
        String transactionId = "TXN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();

        Payment payment = Payment.builder()
            .bookingRef(request.getBookingRef())
            .customerId(customerId)
            .bingeId(getCurrentBingeId())
            .transactionId(transactionId)
            .gatewayOrderId(gatewayOrderId)
            .amount(request.getAmount())
            .paymentMethod(request.getPaymentMethod())
            .status(PaymentStatus.INITIATED)
            .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
            .customerEmail(customerEmail)
            .customerName(customerName)
            .customerPhone(customerPhone)
            .customerPhoneCountryCode(customerPhoneCountryCode)
            .build();

        payment = paymentRepository.save(payment);
        log.info("Payment initiated: {} for booking {}", transactionId, request.getBookingRef());

        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional
    public PaymentDto handleCallback(PaymentCallbackRequest request) {
        log.info("Handling payment callback for gatewayOrderId: {}", request.getGatewayOrderId());

        // Stable, gateway-assigned dedup key. Duplicate or replayed callbacks
        // with the same (orderId, paymentId, status) tuple short-circuit here
        // before any side effects — the explicit Razorpay/Adyen guidance.
        String eventId = webhookDedupService.razorpayEventId(
            request.getGatewayOrderId(), request.getGatewayPaymentId(), request.getStatus());
        if (webhookDedupService.isDuplicate(eventId)) {
            metrics.webhookDuplicate();
            log.info("Duplicate webhook delivery {} — returning cached state", eventId);
            return paymentRepository.findByGatewayOrderIdForUpdate(request.getGatewayOrderId())
                .map(this::toPaymentDtoWithRefunds)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Payment", "gatewayOrderId", request.getGatewayOrderId()));
        }

        Payment payment = paymentRepository.findByGatewayOrderIdForUpdate(request.getGatewayOrderId())
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "gatewayOrderId", request.getGatewayOrderId()));

        // Idempotent: already in a terminal state — return without re-processing
        if (payment.getStatus() == PaymentStatus.SUCCESS
                || payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            log.info("Callback ignored — payment already in terminal state: {}", payment.getStatus());
            return toPaymentDtoWithRefunds(payment);
        }

        // Handle late captures for payments that were already cancelled with the booking.
        if (payment.getStatus() == PaymentStatus.FAILED
                && "Booking cancelled".equals(payment.getFailureReason())) {
            boolean gatewaySuccess = "success".equalsIgnoreCase(request.getStatus())
                || "captured".equalsIgnoreCase(request.getStatus())
                || "authorized".equalsIgnoreCase(request.getStatus());
            if (gatewaySuccess) {
                // Late capture: gateway captured money after booking was cancelled — auto-refund
                log.warn("Late capture detected for cancelled booking — auto-refunding payment {}",
                    payment.getTransactionId());
                PaymentStatus oldStatus = payment.getStatus();
                payment.setStatus(PaymentStatus.SUCCESS);
                payment.setGatewayPaymentId(request.getGatewayPaymentId());
                payment.setPaidAt(LocalDateTime.now());
                payment.setGatewayResponse(buildGatewayResponseSummary(request));
                payment = paymentRepository.save(payment);
                recordStatusChange(payment, oldStatus, PaymentStatus.SUCCESS, "Late capture — booking already cancelled");

                // Create auto-refund inline (we already hold the pessimistic lock)
                String gatewayRefundId = "RFD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
                Refund autoRefund = Refund.builder()
                    .payment(payment)
                    .amount(payment.getAmount())
                    .reason("Auto-refund: booking was cancelled before payment capture")
                    .gatewayRefundId(gatewayRefundId)
                    .status(PaymentStatus.REFUNDED)
                    .initiatedBy("SYSTEM")
                    .refundedAt(LocalDateTime.now())
                    .build();
                refundRepository.save(autoRefund);
                payment.setStatus(PaymentStatus.REFUNDED);
                paymentRepository.save(payment);
                recordStatusChange(payment, PaymentStatus.SUCCESS, PaymentStatus.REFUNDED,
                    "Auto-refund: late capture after booking cancellation");
                publishRefundEvent(payment, autoRefund);
                metrics.refundAutoLate();
                auditLogService.record(
                    "SYSTEM", AuditLogService.ACTION_REFUND_AUTO, "PAYMENT", payment.getTransactionId(),
                    payment.getAmount(), payment.getCurrency(), payment.getBingeId(),
                    java.util.Map.of(
                        "refundId", gatewayRefundId,
                        "bookingRef", payment.getBookingRef(),
                        "trigger", "late_capture_after_cancellation"));
                recordWebhookProcessed(eventId, request);
                return toPaymentDtoWithRefunds(payment);
            }
            // Non-success callback for cancelled booking — ignore silently
            log.info("Ignoring non-success callback for cancelled booking payment: {}", payment.getTransactionId());
            return toPaymentDtoWithRefunds(payment);
        }

        // Reject stale callbacks — payment must have been initiated within the last 24 hours
        if (payment.getCreatedAt() != null && payment.getCreatedAt().isBefore(LocalDateTime.now().minusHours(24))) {
            metrics.webhookStale();
            log.warn("Rejected stale payment callback for gatewayOrderId: {} (created: {})", request.getGatewayOrderId(), payment.getCreatedAt());
            throw new BusinessException("Payment callback expired — payment was initiated more than 24 hours ago", HttpStatus.BAD_REQUEST);
        }

        // Verify Razorpay signature on ALL callbacks to prevent forged success AND failure notifications.
        // Unsigned callbacks are rejected entirely — an attacker could otherwise forge failure
        // notifications to cancel legitimate INITIATED payments.
        if (request.getGatewaySignature() == null || request.getGatewaySignature().isBlank()) {
            metrics.webhookUnsigned();
            log.warn("Rejected unsigned payment callback for gatewayOrderId: {}", request.getGatewayOrderId());
            throw new BusinessException("Payment callback signature is required", HttpStatus.FORBIDDEN);
        }

        String paymentId = request.getGatewayPaymentId() != null ? request.getGatewayPaymentId() : "";
        String payload = request.getGatewayOrderId() + "|" + paymentId;
        if (!verifySignature(payload, request.getGatewaySignature())) {
            metrics.webhookInvalidSignature();
            metrics.signatureFailure();
            log.warn("Invalid payment callback signature for gatewayOrderId: {}", request.getGatewayOrderId());
            throw new BusinessException("Invalid payment signature", HttpStatus.FORBIDDEN);
        }

        metrics.webhookFresh();

        if ("success".equalsIgnoreCase(request.getStatus()) || "captured".equalsIgnoreCase(request.getStatus()) || "authorized".equalsIgnoreCase(request.getStatus())) {
            PaymentStatus oldStatus = payment.getStatus();
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setGatewayPaymentId(request.getGatewayPaymentId());
            payment.setPaidAt(LocalDateTime.now());
            log.info("Payment successful: {}", payment.getTransactionId());
            payment.setGatewayResponse(buildGatewayResponseSummary(request));
            payment = paymentRepository.save(payment);
            recordStatusChange(payment, oldStatus, PaymentStatus.SUCCESS, "Gateway callback: success");
            publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
        } else {
            PaymentStatus oldStatus = payment.getStatus();
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(request.getErrorDescription() != null
                ? request.getErrorDescription()
                : "Payment failed at gateway");
            log.warn("Payment failed: {} — {}", payment.getTransactionId(), payment.getFailureReason());
            payment.setGatewayResponse(buildGatewayResponseSummary(request));
            payment = paymentRepository.save(payment);
            recordStatusChange(payment, oldStatus, PaymentStatus.FAILED, payment.getFailureReason());
            publishPaymentEvent(payment, KafkaTopics.PAYMENT_FAILED);
        }

        // Record the dedup marker AFTER business side effects so retries of a
        // crash between business commit and this call remain safe (we'll simply
        // process the same verified, state-idempotent event again).
        recordWebhookProcessed(eventId, request);

        return toPaymentDtoWithRefunds(payment);
    }

    /**
     * Record the webhook dedup marker in its own transaction. Safe to call
     * even if the outer transaction later rolls back — the worst case is a
     * duplicate being deduped away, which is strictly better than a
     * duplicate firing side effects twice.
     */
    private void recordWebhookProcessed(String eventId, PaymentCallbackRequest request) {
        try {
            String payload = request.getGatewayOrderId() + "|"
                + (request.getGatewayPaymentId() == null ? "" : request.getGatewayPaymentId()) + "|"
                + (request.getStatus() == null ? "" : request.getStatus());
            webhookDedupService.recordNew(eventId, payload);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Concurrent delivery won the race — safe to ignore.
            log.debug("Webhook dedup record already present for {}", eventId);
        } catch (Exception e) {
            log.warn("Failed to record webhook dedup marker for {}: {}", eventId, e.getMessage());
        }
    }

    /**
     * Simulate payment success (development / testing only).
     * Allowed on INITIATED and FAILED payments; FAILED payments are "retried" in-place
     * so the bookingRef matches. Returns existing SUCCESS unchanged (idempotent).
     */
    @Transactional
    public PaymentDto simulatePayment(String transactionId) {
        if (!paymentSimulationEnabled) {
            throw new BusinessException("Payment simulation is disabled", HttpStatus.FORBIDDEN);
        }

        Payment payment = paymentRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "transactionId", transactionId));
        ensurePaymentInCurrentBinge(payment, "transactionId", transactionId);

        // Already succeeded — idempotent return
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return toPaymentDtoWithRefunds(payment);
        }

        // Fully or partially refunded — simulation is not applicable
        if (payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException(
                "Cannot simulate a " + payment.getStatus() + " payment", HttpStatus.CONFLICT);
        }

        // INITIATED or FAILED ? simulate success
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setGatewayPaymentId("SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        payment.setPaidAt(LocalDateTime.now());
        payment.setGatewayResponse("Simulated payment success");
        payment = paymentRepository.save(payment);

        publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
        log.info("Simulated payment success for: {}", transactionId);

        return toPaymentDtoWithRefunds(payment);
    }

    /**
     * Cancel an INITIATED payment (customer-initiated, before it reaches the gateway).
     * The customerId check ensures customers can only cancel their own payments.
     */
    @Transactional
    public PaymentDto cancelPayment(String transactionId, Long requesterId) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "transactionId", transactionId));
        ensurePaymentInCurrentBinge(payment, "transactionId", transactionId);

        if (!payment.getCustomerId().equals(requesterId)) {
            throw new BusinessException("Not authorized to cancel this payment", HttpStatus.FORBIDDEN);
        }

        if (payment.getStatus() != PaymentStatus.INITIATED) {
            throw new BusinessException(
                "Only INITIATED payments can be cancelled. Current status: " + payment.getStatus(),
                HttpStatus.BAD_REQUEST);
        }

        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason("Cancelled by customer");
        payment = paymentRepository.save(payment);

        auditLogService.record(
            String.valueOf(requesterId), AuditLogService.ACTION_PAYMENT_CANCEL, "PAYMENT",
            payment.getTransactionId(), payment.getAmount(), payment.getCurrency(),
            payment.getBingeId(),
            java.util.Map.of("bookingRef", payment.getBookingRef()));

        log.info("Payment {} cancelled by customer {}", transactionId, requesterId);
        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional(readOnly = true)
    public PaymentDto getPaymentByTransactionId(String transactionId, Long requesterId, String requesterRole) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "transactionId", transactionId));
        ensurePaymentInCurrentBinge(payment, "transactionId", transactionId);
        ensurePaymentAccess(payment, requesterId, requesterRole);
        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> getPaymentsByBookingRef(String bookingRef, Long requesterId, String requesterRole) {
        List<Payment> payments = findPaymentsByBookingRefForCurrentBinge(bookingRef);
        boolean admin = isAdminRole(requesterRole);
        if (payments.isEmpty()) {
            // Empty list previously short-circuited as 200 [] for everyone.
            // For non-admin callers that lets an attacker enumerate booking refs
            // (no payments yet ⇒ same response as a real-but-empty result, so they
            // can probe the booking-ref keyspace). Treat unknown / non-owned as 404
            // so a foreign ref is indistinguishable from a non-existent one.
            if (admin) {
                return List.of();
            }
            throw new ResourceNotFoundException("Payment", "bookingRef", bookingRef);
        }
        if (admin) {
            return toPaymentDtoListWithRefunds(payments);
        }

        List<Payment> ownedPayments = payments.stream()
                .filter(payment -> payment.getCustomerId() != null && payment.getCustomerId().equals(requesterId))
                .toList();
        if (ownedPayments.isEmpty()) {
            throw new BusinessException("Not authorized to access payments for this booking", HttpStatus.FORBIDDEN);
        }

        return toPaymentDtoListWithRefunds(ownedPayments);
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> getCustomerPayments(Long customerId) {
        return toPaymentDtoListWithRefunds(findCustomerPaymentsForCurrentBinge(customerId));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<PaymentDto> getCustomerPaymentsPaginated(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        Long bingeId = com.skbingegalaxy.common.context.BingeContext.getBingeId();
        org.springframework.data.domain.Page<Payment> page = (bingeId != null)
            ? paymentRepository.findByCustomerIdAndBingeIdOrderByCreatedAtDesc(customerId, bingeId, pageable)
            : paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        return page.map(p -> {
            BigDecimal totalRefunded = refundRepository.sumCompletedRefundsByPaymentId(p.getId(), REFUNDED_STATUSES);
            BigDecimal remaining = p.getAmount().subtract(totalRefunded);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
            int refundCount = (int) refundRepository.countByPaymentIdAndStatusIn(p.getId(), REFUNDED_STATUSES);
            return buildPaymentDto(p, totalRefunded, remaining, refundCount);
        });
    }

    /**
     * Initiate a refund for an admin. Key safeguards:
     * <ul>
     *   <li>Pessimistic write lock prevents concurrent over-refund from parallel requests.</li>
     *   <li>DB-level SUM (not in-memory stream) for authoritative remaining-refundable amount.</li>
     *   <li>Publishes a Kafka event so the notification service can email the customer.</li>
     * </ul>
     */
    @Transactional
    public RefundDto initiateRefund(RefundRequest request, String initiatedBy) {
        // Pessimistic lock: only one thread can refund this payment at a time
        Payment payment = paymentRepository.findByIdForUpdate(request.getPaymentId())
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "id",
                request.getPaymentId().toString()));
        ensurePaymentInCurrentBinge(payment, "id", request.getPaymentId());

        // Only SUCCESS / PARTIALLY_REFUNDED payments are eligible
        if (payment.getStatus() != PaymentStatus.SUCCESS
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException(
                "Cannot refund a payment with status: " + payment.getStatus()
                    + ". Only SUCCESS or PARTIALLY_REFUNDED payments are eligible.",
                HttpStatus.BAD_REQUEST);
        }

        // Validate amount field
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ONE) < 0) {
            throw new BusinessException("Refund amount must be at least ₹1.00", HttpStatus.BAD_REQUEST);
        }

        // DB-level authoritative sum to prevent race-condition over-refunds
        BigDecimal alreadyRefunded = refundRepository.sumCompletedRefundsByPaymentId(payment.getId(), REFUNDED_STATUSES);
        BigDecimal remaining = payment.getAmount().subtract(alreadyRefunded);

        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("This payment has already been fully refunded", HttpStatus.CONFLICT);
        }

        if (request.getAmount().compareTo(remaining) > 0) {
            throw new BusinessException(
                String.format("Refund amount ₹%.2f exceeds remaining refundable amount ₹%.2f",
                    request.getAmount(), remaining),
                HttpStatus.BAD_REQUEST);
        }

        String gatewayRefundId = "RFD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();

        Refund refund = Refund.builder()
            .payment(payment)
            .amount(request.getAmount())
            .reason(request.getReason())
            .gatewayRefundId(gatewayRefundId)
            .status(PaymentStatus.REFUNDED)
            .refundStatus(com.skbingegalaxy.payment.entity.RefundStatus.SUCCEEDED)
            .initiatedBy(initiatedBy)
            .refundedAt(LocalDateTime.now())
            .build();

        refund = refundRepository.save(refund);

        // Update payment status
        BigDecimal newTotalRefunded = alreadyRefunded.add(request.getAmount());
        if (newTotalRefunded.compareTo(payment.getAmount()) >= 0) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        }
        paymentRepository.save(payment);

        // Notify downstream (e.g. notification service sends refund email)
        publishRefundEvent(payment, refund);

        metrics.refundIssued();
        auditLogService.record(
            initiatedBy, AuditLogService.ACTION_REFUND_ISSUED, "PAYMENT", payment.getTransactionId(),
            request.getAmount(), payment.getCurrency(), payment.getBingeId(),
            java.util.Map.of(
                "refundId", gatewayRefundId,
                "reason", request.getReason() == null ? "" : request.getReason(),
                "bookingRef", payment.getBookingRef()));

        log.info("Refund {} of ₹{} by {} for payment {} (booking: {})",
            gatewayRefundId, request.getAmount(), initiatedBy,
            payment.getTransactionId(), payment.getBookingRef());

        return toRefundDto(refund);
    }

    /**
     * Record a cash payment collected directly by admin.
     * Creates a SUCCESS payment record so refunds can later be issued against it.
     * Idempotent: if a SUCCESS record already exists for the booking, returns it unchanged.
     */
    @Transactional
    public PaymentDto recordCashPayment(RecordCashPaymentRequest request, String adminEmail) {
        log.info("Recording cash payment for booking: {} by admin: {}", request.getBookingRef(), adminEmail);

        // Idempotency: a SUCCESS payment already exists — return it
        var existingSuccess = findSuccessfulPaymentsForCurrentBinge(request.getBookingRef());
        if (!existingSuccess.isEmpty()) {
            log.info("Cash payment already recorded for booking {}", request.getBookingRef());
            return toPaymentDtoWithRefunds(existingSuccess.get(0));
        }

        String transactionId = "CASH-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String gatewayOrderId = "CASH-ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Payment payment = Payment.builder()
            .bookingRef(request.getBookingRef())
            .customerId(request.getCustomerId())
            .bingeId(getCurrentBingeId())
            .transactionId(transactionId)
            .gatewayOrderId(gatewayOrderId)
            .amount(request.getAmount())
            .paymentMethod(PaymentMethod.CASH)
            .status(PaymentStatus.SUCCESS)
            .currency("INR")
            .paidAt(LocalDateTime.now())
            .gatewayResponse("Cash payment recorded by admin: " + adminEmail
                + (request.getNotes() != null && !request.getNotes().isBlank()
                    ? " | " + request.getNotes() : ""))
            .build();

        payment = paymentRepository.save(payment);
        publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
        auditLogService.record(
            adminEmail, AuditLogService.ACTION_CASH_RECORDED, "PAYMENT", transactionId,
            request.getAmount(), "INR", payment.getBingeId(),
            java.util.Map.of(
                "bookingRef", request.getBookingRef(),
                "notes", request.getNotes() == null ? "" : request.getNotes()));
        log.info("Cash payment {} recorded for booking {} by admin {}",
            transactionId, request.getBookingRef(), adminEmail);

        return toPaymentDtoWithRefunds(payment);
    }

    /**
     * Record an additional payment for a booking with any payment method.
     * Used for split payments, method changes, or collecting remaining balances.
     * Idempotency guard: rejects duplicate requests with the same booking, method, and amount within 30 seconds.
     */
    @Transactional
    public PaymentDto addPayment(AddPaymentRequest request, String adminEmail) {
        log.info("Adding {} payment of {} for booking {} by admin {}",
            request.getPaymentMethod(), request.getAmount(), request.getBookingRef(), adminEmail);

        // Idempotency guard: reject if an identical payment was recorded within the dedup window
        List<Payment> recentDupes = findRecentDuplicatesForCurrentBinge(
            request.getBookingRef(), request.getPaymentMethod(),
            request.getAmount(), LocalDateTime.now().minusSeconds(dedupWindowSeconds));
        if (!recentDupes.isEmpty()) {
            log.info("Duplicate addPayment detected for booking {} — returning existing payment {}",
                    request.getBookingRef(), recentDupes.get(0).getTransactionId());
            return toPaymentDtoWithRefunds(recentDupes.get(0));
        }

        // Over-collection guard: reject if cumulative payments would exceed booking total
        BigDecimal existingTotal = sumSuccessfulPaymentsForCurrentBinge(request.getBookingRef());
        if (existingTotal == null) existingTotal = BigDecimal.ZERO;
        BigDecimal projectedTotal = existingTotal.add(request.getAmount());
        if (request.getBookingTotalAmount() != null
                && projectedTotal.compareTo(request.getBookingTotalAmount()) > 0) {
            throw new BusinessException(
                String.format("Payment rejected — would over-collect: existing ₹%s + ₹%s = ₹%s exceeds booking total ₹%s",
                    existingTotal, request.getAmount(), projectedTotal, request.getBookingTotalAmount()),
                HttpStatus.CONFLICT);
        }
        if (projectedTotal.compareTo(existingTotal) > 0 && existingTotal.compareTo(BigDecimal.ZERO) > 0) {
            log.info("Over-collection check for booking {}: existing={}, adding={}, projected={}",
                    request.getBookingRef(), existingTotal, request.getAmount(), projectedTotal);
        }

        String prefix = request.getPaymentMethod().name();
        String transactionId = prefix + "-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String gatewayOrderId = "ADM-ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Payment payment = Payment.builder()
            .bookingRef(request.getBookingRef())
            .customerId(request.getCustomerId() != null ? request.getCustomerId() : 0L)
            .bingeId(getCurrentBingeId())
            .transactionId(transactionId)
            .gatewayOrderId(gatewayOrderId)
            .amount(request.getAmount())
            .paymentMethod(request.getPaymentMethod())
            .status(PaymentStatus.SUCCESS)
            .currency("INR")
            .paidAt(LocalDateTime.now())
            .gatewayResponse("Payment recorded by admin: " + adminEmail
                + (request.getNotes() != null && !request.getNotes().isBlank()
                    ? " | " + request.getNotes() : ""))
            .build();

        payment = paymentRepository.save(payment);
        publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
        auditLogService.record(
            adminEmail, AuditLogService.ACTION_PAYMENT_ADDED, "PAYMENT", transactionId,
            request.getAmount(), "INR", payment.getBingeId(),
            java.util.Map.of(
                "bookingRef", request.getBookingRef(),
                "method", request.getPaymentMethod().name(),
                "notes", request.getNotes() == null ? "" : request.getNotes()));
        log.info("Additional payment {} recorded for booking {} by admin {}",
            transactionId, request.getBookingRef(), adminEmail);

        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional(readOnly = true)
    public List<RefundDto> getRefundsForPayment(Long paymentId) {
        // Verify payment exists
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId.toString()));
        ensurePaymentInCurrentBinge(payment, "id", paymentId);
        return refundRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId)
            .stream().map(this::toRefundDto).toList();
    }

    /**
     * Customer-facing refund timeline for a booking. Returns every refund row
     * (any lifecycle state) in reverse-chronological order so the UI can show
     * "Refund initiated → processing → succeeded" history. Tenancy-scoped.
     */
    @Transactional(readOnly = true)
    public List<RefundDto> getRefundsForBooking(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        var rows = (bingeId != null)
            ? refundRepository.findByBookingRefAndBingeIdOrderByCreatedAtDesc(bookingRef, bingeId)
            : refundRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef);
        return rows.stream().map(this::toRefundDto).toList();
    }

    /**
     * Admin failed-refund queue. Lists every refund whose own per-attempt
     * lifecycle ended in {@link com.skbingegalaxy.payment.entity.RefundStatus#FAILED},
     * scoped to the currently selected binge.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RefundDto> getFailedRefunds(
            org.springframework.data.domain.Pageable pageable) {
        Long bingeId = getCurrentBingeId();
        if (bingeId == null) {
            throw new BusinessException(
                "No binge selected for failed-refund query", HttpStatus.BAD_REQUEST);
        }
        return refundRepository
            .findByRefundStatusAndPayment_BingeIdOrderByCreatedAtDesc(
                com.skbingegalaxy.payment.entity.RefundStatus.FAILED, bingeId, pageable)
            .map(this::toRefundDto);
    }

    /**
     * Admin retry of a failed refund. Marks the original row as
     * {@link com.skbingegalaxy.payment.entity.RefundStatus#SUPERSEDED}, creates
     * a new {@link com.skbingegalaxy.payment.entity.RefundStatus#INITIATED}
     * attempt linked via {@code retry_of_id}, and (today, with the synchronous
     * gateway path) immediately settles it as {@code SUCCEEDED}.
     *
     * <p>The over-refund and pessimistic-lock guards from {@link #initiateRefund}
     * are reused so a retry can never push the parent payment past its full
     * refundable amount.
     */
    @Transactional
    public RefundDto retryFailedRefund(Long refundId, String adminEmail) {
        Refund original = refundRepository.findById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund", "id", refundId.toString()));
        ensurePaymentInCurrentBinge(original.getPayment(), "id", original.getPayment().getId());

        if (original.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.FAILED) {
            throw new BusinessException(
                "Only FAILED refunds can be retried. Current: " + original.getRefundStatus(),
                HttpStatus.CONFLICT);
        }

        // Maker-checker gate: above the configured threshold, the request must
        // be approved by a different admin first. We create the request here
        // and short-circuit with a 202 ACCEPTED via a typed business exception
        // carrying the approval id, so the caller can poll/notify the second
        // admin. Once approved, the executor calls
        // executeApprovedRefundRetry(approvalId) which performs this method's
        // body without re-checking the threshold.
        if (refundRetryApprovalThreshold != null
                && original.getAmount().compareTo(refundRetryApprovalThreshold) > 0) {
            // Are we already past an APPROVED gate for this same refund?
            // If yes, fall through and execute. If no, create a new request.
            // Here we only INITIATE — the executor path is on the
            // /admin/approvals/{id}/execute-refund-retry endpoint.
            com.skbingegalaxy.payment.entity.AdminApprovalRequest req = approvalService.createRequest(
                "REFUND_RETRY",
                "REFUND",
                String.valueOf(original.getId()),
                original.getAmount(),
                original.getPayment().getCurrency(),
                original.getPayment().getBingeId(),
                java.util.Map.of(
                    "refundId", String.valueOf(original.getId()),
                    "paymentId", String.valueOf(original.getPayment().getId()),
                    "bookingRef", original.getPayment().getBookingRef()),
                adminEmail,
                null,
                "Refund retry above threshold of " + refundRetryApprovalThreshold);
            throw new BusinessException(
                "Refund retry above ₹" + refundRetryApprovalThreshold
                    + " requires a second admin's approval. "
                    + "Approval request id: " + req.getId(),
                HttpStatus.ACCEPTED);
        }

        return doRetryFailedRefund(original, adminEmail, null);
    }

    /**
     * Domain action invoked by the maker-checker controller after a different
     * admin has APPROVED the request. Re-validates the approval, executes the
     * retry without the threshold gate, and stamps the approval as EXECUTED
     * so the same approval can never be replayed.
     */
    @Transactional
    public java.util.Map<String, Object> executeApprovedRefundRetry(Long approvalId, String executorEmail) {
        com.skbingegalaxy.payment.dto.AdminApprovalRequestDto approval = approvalService.get(approvalId);
        if (!"REFUND_RETRY".equals(approval.getActionType())) {
            throw new BusinessException(
                "Approval is for action " + approval.getActionType()
                    + " — not REFUND_RETRY",
                HttpStatus.BAD_REQUEST);
        }
        if (!"APPROVED".equals(approval.getStatus())) {
            throw new BusinessException(
                "Only APPROVED approvals can be executed. Current: " + approval.getStatus(),
                HttpStatus.CONFLICT);
        }
        Long refundId = Long.parseLong(approval.getResourceId());
        Refund original = refundRepository.findById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund", "id", refundId.toString()));
        if (original.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.FAILED) {
            throw new BusinessException(
                "Underlying refund is no longer FAILED (now: "
                    + original.getRefundStatus() + ") — approval cannot be executed",
                HttpStatus.CONFLICT);
        }
        RefundDto retried = doRetryFailedRefund(original, executorEmail, approvalId);
        approvalService.markExecuted(approvalId,
            "Refund retried; new gateway id: " + retried.getGatewayRefundId());
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("approvalId", approvalId);
        body.put("refund", retried);
        return body;
    }

    /**
     * Inner refund-retry: shared by direct (below-threshold) and approval-driven
     * (above-threshold) call sites. Holds all the pessimistic-lock + over-refund
     * + audit + outbox publishing logic.
     */
    private RefundDto doRetryFailedRefund(Refund original, String adminEmail, Long approvalId) {
        // Reuse the same guards as initiateRefund — pessimistic lock, over-refund check.
        Payment payment = paymentRepository.findByIdForUpdate(original.getPayment().getId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Payment", "id", original.getPayment().getId().toString()));

        BigDecimal alreadyRefunded = refundRepository.sumCompletedRefundsByPaymentId(
            payment.getId(), REFUNDED_STATUSES);
        BigDecimal remaining = payment.getAmount().subtract(alreadyRefunded);
        if (remaining.compareTo(original.getAmount()) < 0) {
            throw new BusinessException(
                String.format(
                    "Retry would over-refund: requested ₹%.2f but only ₹%.2f remaining",
                    original.getAmount(), remaining),
                HttpStatus.CONFLICT);
        }

        // Mark original as SUPERSEDED and bump its retry count
        original.setRefundStatus(com.skbingegalaxy.payment.entity.RefundStatus.SUPERSEDED);
        original.setRetryCount(original.getRetryCount() + 1);
        refundRepository.save(original);

        String gatewayRefundId = "RFD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        Refund retry = Refund.builder()
            .payment(payment)
            .amount(original.getAmount())
            .reason(original.getReason())
            .gatewayRefundId(gatewayRefundId)
            .status(PaymentStatus.REFUNDED)
            .refundStatus(com.skbingegalaxy.payment.entity.RefundStatus.SUCCEEDED)
            .retryOfId(original.getId())
            .retryCount(0)
            .initiatedBy(adminEmail)
            .refundedAt(LocalDateTime.now())
            .build();
        retry = refundRepository.save(retry);

        // Settle parent payment status
        BigDecimal newTotalRefunded = alreadyRefunded.add(retry.getAmount());
        if (newTotalRefunded.compareTo(payment.getAmount()) >= 0) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        }
        paymentRepository.save(payment);

        publishRefundEvent(payment, retry);
        metrics.refundIssued();
        java.util.Map<String, String> auditMeta = new java.util.LinkedHashMap<>();
        auditMeta.put("refundId", gatewayRefundId);
        auditMeta.put("retryOfRefundId", String.valueOf(original.getId()));
        auditMeta.put("bookingRef", payment.getBookingRef());
        if (approvalId != null) auditMeta.put("approvalId", String.valueOf(approvalId));
        auditLogService.record(
            adminEmail, AuditLogService.ACTION_REFUND_ISSUED, "PAYMENT",
            payment.getTransactionId(),
            retry.getAmount(), payment.getCurrency(), payment.getBingeId(),
            java.util.Map.copyOf(auditMeta));
        log.info("Refund retry {} of {} (orig refund id {}) by admin {} for booking {}{}",
            gatewayRefundId, retry.getAmount(), original.getId(), adminEmail, payment.getBookingRef(),
            approvalId != null ? " (approval " + approvalId + ")" : "");
        return toRefundDto(retry);
    }

    /**
     * Admin dashboard statistics for the payment service.
     */
    @Transactional(readOnly = true, timeout = 10)
    public Map<String, Object> getPaymentStats() {
        BigDecimal totalRevenue   = getTotalSuccessfulPaymentsForCurrentBinge();
        BigDecimal totalRefunded  = getTotalRefundedForCurrentBinge();
        long successCount         = countPaymentsByStatusForCurrentBinge(PaymentStatus.SUCCESS);
        long failedCount          = countPaymentsByStatusForCurrentBinge(PaymentStatus.FAILED);
        long initiatedCount       = countPaymentsByStatusForCurrentBinge(PaymentStatus.INITIATED);
        long refundedCount        = countPaymentsByStatusForCurrentBinge(PaymentStatus.REFUNDED);
        long partialCount         = countPaymentsByStatusForCurrentBinge(PaymentStatus.PARTIALLY_REFUNDED);

        return Map.of(
            "totalRevenue",            totalRevenue,
            "totalRefunded",           totalRefunded,
            "netRevenue",              totalRevenue.subtract(totalRefunded),
            "successCount",            successCount,
            "failedCount",             failedCount,
            "initiatedCount",          initiatedCount,
            "refundedCount",           refundedCount,
            "partiallyRefundedCount",  partialCount
        );
    }

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void recordStatusChange(Payment payment, PaymentStatus from, PaymentStatus to, String reason) {
        statusHistoryRepository.save(com.skbingegalaxy.payment.entity.PaymentStatusHistory.builder()
            .paymentId(payment.getId())
            .bookingRef(payment.getBookingRef())
            .fromStatus(from)
            .toStatus(to)
            .reason(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason)
            .build());
    }

    private void publishPaymentEvent(Payment payment, String topic) {
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef(payment.getBookingRef())
            .transactionId(payment.getTransactionId())
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .paymentMethod(payment.getPaymentMethod().name())
            .status(payment.getStatus().name())
            .customerEmail(payment.getCustomerEmail())
            .customerName(payment.getCustomerName())
            .customerPhone(payment.getCustomerPhone())
            .customerPhoneCountryCode(payment.getCustomerPhoneCountryCode())
            .paidAt(payment.getPaidAt())
            .build();

        eventPublisher.publishEvent(new PaymentKafkaEvent(topic, payment.getBookingRef(), event));
        log.debug("Queued {} event for booking: {} (publishes after commit)", topic, payment.getBookingRef());
    }

    private void publishRefundEvent(Payment payment, Refund refund) {
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef(payment.getBookingRef())
            .transactionId(payment.getTransactionId())
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .paymentMethod(payment.getPaymentMethod().name())
            .status(payment.getStatus().name())
            .refundId(refund.getGatewayRefundId())
            .refundAmount(refund.getAmount())
            .refundReason(refund.getReason())
            .build();

        eventPublisher.publishEvent(new PaymentKafkaEvent(KafkaTopics.PAYMENT_REFUNDED, payment.getBookingRef(), event));
        log.debug("Queued payment.refunded event for booking: {} (publishes after commit)", payment.getBookingRef());
    }

    private String buildGatewayResponseSummary(PaymentCallbackRequest req) {
        return String.format("status=%s, paymentId=%s, error=%s",
            req.getStatus(), req.getGatewayPaymentId(), req.getErrorDescription());
    }

    /**
     * Maps a Payment to PaymentDto and enriches it with refund summary fields
     * (totalRefunded, remainingRefundable, refundCount) via DB aggregation.
     */
    private PaymentDto toPaymentDtoWithRefunds(Payment p) {
        BigDecimal totalRefunded = refundRepository.sumCompletedRefundsByPaymentId(p.getId(), REFUNDED_STATUSES);
        BigDecimal remaining = p.getAmount().subtract(totalRefunded);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
        int refundCount = (int) refundRepository.countByPaymentIdAndStatusIn(p.getId(), REFUNDED_STATUSES);

        return buildPaymentDto(p, totalRefunded, remaining, refundCount);
    }

    /**
     * Batch-optimised: maps a list of Payments to PaymentDtos with only 2 DB queries total
     * instead of 2×N (avoids the N+1 problem for list endpoints).
     */
    private List<PaymentDto> toPaymentDtoListWithRefunds(List<Payment> payments) {
        if (payments.isEmpty()) return List.of();

        List<Long> ids = payments.stream().map(Payment::getId).toList();
        Map<Long, BigDecimal> sumMap = new java.util.HashMap<>();
        for (Object[] row : refundRepository.sumCompletedRefundsByPaymentIds(ids, REFUNDED_STATUSES)) {
            sumMap.put((Long) row[0], (BigDecimal) row[1]);
        }
        Map<Long, Long> countMap = new java.util.HashMap<>();
        for (Object[] row : refundRepository.countRefundsByPaymentIds(ids, REFUNDED_STATUSES)) {
            countMap.put((Long) row[0], (Long) row[1]);
        }

        return payments.stream().map(p -> {
            BigDecimal totalRefunded = sumMap.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal remaining = p.getAmount().subtract(totalRefunded);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
            int refundCount = countMap.getOrDefault(p.getId(), 0L).intValue();
            return buildPaymentDto(p, totalRefunded, remaining, refundCount);
        }).toList();
    }

    private PaymentDto buildPaymentDto(Payment p, BigDecimal totalRefunded,
                                        BigDecimal remaining, int refundCount) {
        return PaymentDto.builder()
            .id(p.getId())
            .bookingRef(p.getBookingRef())
            .customerId(p.getCustomerId())
            .transactionId(p.getTransactionId())
            .gatewayOrderId(p.getGatewayOrderId())
            .gatewayPaymentId(p.getGatewayPaymentId())
            .amount(p.getAmount())
            .gatewayFee(p.getGatewayFee())
            .tax(p.getTax())
            .paymentMethod(p.getPaymentMethod())
            .status(p.getStatus())
            .currency(p.getCurrency())
            .failureReason(p.getFailureReason())
            .paidAt(p.getPaidAt())
            .createdAt(p.getCreatedAt())
            .totalRefunded(totalRefunded)
            .remainingRefundable(remaining)
            .refundCount(refundCount)
            .razorpayKeyId(p.getStatus() == PaymentStatus.INITIATED ? razorpayKeyId : null)
            .build();
    }

    private RefundDto toRefundDto(Refund r) {
        return RefundDto.builder()
            .id(r.getId())
            .paymentId(r.getPayment().getId())
            .bookingRef(r.getPayment().getBookingRef())
            .amount(r.getAmount())
            .reason(r.getReason())
            .gatewayRefundId(r.getGatewayRefundId())
            .status(r.getStatus())
            .refundStatus(r.getRefundStatus())
            .retryOfId(r.getRetryOfId())
            .retryCount(r.getRetryCount())
            .failureReason(r.getFailureReason())
            .initiatedBy(r.getInitiatedBy())
            .refundedAt(r.getRefundedAt())
            .createdAt(r.getCreatedAt())
            .build();
    }

    private Long getCurrentBingeId() {
        return BingeContext.getBingeId();
    }

    private List<Payment> findSuccessfulPaymentsForCurrentBinge(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findByBookingRefAndStatusAndBingeId(bookingRef, PaymentStatus.SUCCESS, bingeId)
            : paymentRepository.findByBookingRefAndStatus(bookingRef, PaymentStatus.SUCCESS);
    }

    private Optional<Payment> findExistingInitiatedPaymentForCurrentBinge(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findFirstByBookingRefAndStatusAndBingeIdOrderByCreatedAtDesc(bookingRef, PaymentStatus.INITIATED, bingeId)
            : paymentRepository.findFirstByBookingRefAndStatusOrderByCreatedAtDesc(bookingRef, PaymentStatus.INITIATED);
    }

    private List<Payment> findPaymentsByBookingRefForCurrentBinge(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findByBookingRefAndBingeIdOrderByCreatedAtDesc(bookingRef, bingeId)
            : paymentRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef);
    }

    private List<Payment> findCustomerPaymentsForCurrentBinge(Long customerId) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findByCustomerIdAndBingeIdOrderByCreatedAtDesc(customerId, bingeId)
            : paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    private List<Payment> findRecentDuplicatesForCurrentBinge(String bookingRef, PaymentMethod paymentMethod,
                                                              BigDecimal amount, LocalDateTime since) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findRecentDuplicatesByBingeId(bookingRef, paymentMethod, amount, bingeId, since)
            : paymentRepository.findRecentDuplicates(bookingRef, paymentMethod, amount, since);
    }

    private BigDecimal sumSuccessfulPaymentsForCurrentBinge(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.sumSuccessfulPaymentsByBookingRefAndBingeId(bookingRef, bingeId)
            : paymentRepository.sumSuccessfulPaymentsByBookingRef(bookingRef);
    }

    private BigDecimal getTotalSuccessfulPaymentsForCurrentBinge() {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.getTotalSuccessfulPaymentsByBingeId(bingeId)
            : paymentRepository.getTotalSuccessfulPayments();
    }

    private BigDecimal getTotalRefundedForCurrentBinge() {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? refundRepository.sumAllCompletedRefundsByBingeId(REFUNDED_STATUSES, bingeId)
            : refundRepository.sumAllCompletedRefunds(REFUNDED_STATUSES);
    }

    private long countPaymentsByStatusForCurrentBinge(PaymentStatus status) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.countByStatusAndBingeId(status, bingeId)
            : paymentRepository.countByStatus(status);
    }

    private void ensurePaymentInCurrentBinge(Payment payment, String field, Object value) {
        Long bingeId = getCurrentBingeId();
        if (bingeId != null && !bingeId.equals(payment.getBingeId())) {
            throw new ResourceNotFoundException("Payment", field, value);
        }
    }

    private void ensurePaymentAccess(Payment payment, Long requesterId, String requesterRole) {
        if (isAdminRole(requesterRole)) {
            return;
        }
        if (requesterId == null || payment.getCustomerId() == null || !payment.getCustomerId().equals(requesterId)) {
            throw new BusinessException("Not authorized to access this payment", HttpStatus.FORBIDDEN);
        }
    }

    private boolean isAdminRole(String requesterRole) {
        return "ADMIN".equalsIgnoreCase(requesterRole) || "SUPER_ADMIN".equalsIgnoreCase(requesterRole);
    }

    /**
     * Verify HMAC-SHA256 signature from Razorpay webhook/callback.
     */
    private boolean verifySignature(String payload, String expectedSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(razorpayKeySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String generated = bytesToHex(hash);
            return java.security.MessageDigest.isEqual(
                generated.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedSignature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Signature verification failed", e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
