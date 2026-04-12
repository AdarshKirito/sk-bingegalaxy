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
import com.skbingegalaxy.payment.repository.PaymentRepository;
import com.skbingegalaxy.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
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
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.razorpay.key-secret}")
    private String razorpayKeySecret;

    @Value("${app.razorpay.key-id:}")
    private String razorpayKeyId;

    @Value("${app.payment.simulation-enabled:false}")
    private boolean paymentSimulationEnabled;

    @Value("${app.payment.dedup-window-seconds:30}")
    private int dedupWindowSeconds;

    @jakarta.annotation.PostConstruct
    void validateConfig() {
        if (!paymentSimulationEnabled && (razorpayKeySecret == null || razorpayKeySecret.isBlank())) {
            throw new IllegalStateException(
                "RAZORPAY_KEY_SECRET must be set when payment simulation is disabled");
        }
    }

    @Transactional
    public PaymentDto initiatePayment(InitiatePaymentRequest request, Long customerId, String customerEmail, String customerName) {
        log.info("Initiating payment for booking: {}, amount: {}", request.getBookingRef(), request.getAmount());

        // Serialise concurrent initiations for the same booking to prevent duplicate INITIATED payments
        paymentRepository.acquirePaymentLock(request.getBookingRef().hashCode());

        // Guard 1: payment already succeeded for this booking — reject duplicate attempt
        if (!findSuccessfulPaymentsForCurrentBinge(request.getBookingRef()).isEmpty()) {
            throw new BusinessException("Payment already completed for booking " + request.getBookingRef(), HttpStatus.CONFLICT);
        }

        // Guard 2 (idempotency): an INITIATED payment already exists â€” return it
        // instead of creating a duplicate (handles network-retry / double-click scenarios)
        var existing = findExistingInitiatedPaymentForCurrentBinge(request.getBookingRef());
        if (existing.isPresent()) {
            log.info("Returning existing INITIATED payment {} for booking {}",
                    existing.get().getTransactionId(), request.getBookingRef());
            return toPaymentDtoWithRefunds(existing.get());
        }

        String transactionId = "TXN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String gatewayOrderId;
        if (!paymentSimulationEnabled && razorpayKeyId != null && !razorpayKeyId.isBlank()) {
            gatewayOrderId = createRazorpayOrder(
                request.getAmount(),
                request.getCurrency() != null ? request.getCurrency() : "INR",
                request.getBookingRef());
        } else {
            gatewayOrderId = "ORD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        }

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
            .build();

        payment = paymentRepository.save(payment);
        log.info("Payment initiated: {} for booking {}", transactionId, request.getBookingRef());

        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional
    public PaymentDto handleCallback(PaymentCallbackRequest request) {
        log.info("Handling payment callback for gatewayOrderId: {}", request.getGatewayOrderId());

        Payment payment = paymentRepository.findByGatewayOrderId(request.getGatewayOrderId())
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "gatewayOrderId", request.getGatewayOrderId()));

        // Idempotent: already in a terminal state — return without re-processing
        if (payment.getStatus() == PaymentStatus.SUCCESS
                || payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            log.info("Callback ignored — payment already in terminal state: {}", payment.getStatus());
            return toPaymentDtoWithRefunds(payment);
        }

        // Reject callbacks for payments that were already cancelled with the booking.
        // If money was actually captured by Razorpay, a manual refund may be needed.
        if (payment.getStatus() == PaymentStatus.FAILED
                && "Booking cancelled".equals(payment.getFailureReason())) {
            log.warn("Callback rejected — payment {} was cancelled with its booking. "
                    + "If the gateway captured funds, a manual refund is required.", payment.getTransactionId());
            throw new BusinessException("Payment was cancelled because the booking was cancelled", HttpStatus.CONFLICT);
        }

        // Reject stale callbacks — payment must have been initiated within the last 24 hours
        if (payment.getCreatedAt() != null && payment.getCreatedAt().isBefore(LocalDateTime.now().minusHours(24))) {
            log.warn("Rejected stale payment callback for gatewayOrderId: {} (created: {})", request.getGatewayOrderId(), payment.getCreatedAt());
            throw new BusinessException("Payment callback expired — payment was initiated more than 24 hours ago", HttpStatus.BAD_REQUEST);
        }

        // Verify Razorpay signature on ALL callbacks to prevent forged success AND failure notifications.
        // Unsigned callbacks are rejected entirely — an attacker could otherwise forge failure
        // notifications to cancel legitimate INITIATED payments.
        if (request.getGatewaySignature() == null || request.getGatewaySignature().isBlank()) {
            log.warn("Rejected unsigned payment callback for gatewayOrderId: {}", request.getGatewayOrderId());
            throw new BusinessException("Payment callback signature is required", HttpStatus.FORBIDDEN);
        }

        String paymentId = request.getGatewayPaymentId() != null ? request.getGatewayPaymentId() : "";
        String payload = request.getGatewayOrderId() + "|" + paymentId;
        if (!verifySignature(payload, request.getGatewaySignature())) {
            log.warn("Invalid payment callback signature for gatewayOrderId: {}", request.getGatewayOrderId());
            throw new BusinessException("Invalid payment signature", HttpStatus.FORBIDDEN);
        }

        if ("success".equalsIgnoreCase(request.getStatus()) || "captured".equalsIgnoreCase(request.getStatus())) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setGatewayPaymentId(request.getGatewayPaymentId());
            payment.setPaidAt(LocalDateTime.now());
            log.info("Payment successful: {}", payment.getTransactionId());
            payment.setGatewayResponse(buildGatewayResponseSummary(request));
            payment = paymentRepository.save(payment);
            publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(request.getErrorDescription() != null
                ? request.getErrorDescription()
                : "Payment failed at gateway");
            log.warn("Payment failed: {} â€” {}", payment.getTransactionId(), payment.getFailureReason());
            payment.setGatewayResponse(buildGatewayResponseSummary(request));
            payment = paymentRepository.save(payment);
            publishPaymentEvent(payment, KafkaTopics.PAYMENT_FAILED);
        }

        return toPaymentDtoWithRefunds(payment);
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

        // Already succeeded â€” idempotent return
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return toPaymentDtoWithRefunds(payment);
        }

        // Fully or partially refunded â€” simulation is not applicable
        if (payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException(
                "Cannot simulate a " + payment.getStatus() + " payment", HttpStatus.CONFLICT);
        }

        // INITIATED or FAILED â†’ simulate success
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
        if (payments.isEmpty()) {
            return List.of();
        }
        if (isAdminRole(requesterRole)) {
            return payments.stream().map(this::toPaymentDtoWithRefunds).toList();
        }

        List<Payment> ownedPayments = payments.stream()
                .filter(payment -> payment.getCustomerId() != null && payment.getCustomerId().equals(requesterId))
                .toList();
        if (ownedPayments.isEmpty()) {
            throw new BusinessException("Not authorized to access payments for this booking", HttpStatus.FORBIDDEN);
        }

        return ownedPayments.stream().map(this::toPaymentDtoWithRefunds).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> getCustomerPayments(Long customerId) {
        return findCustomerPaymentsForCurrentBinge(customerId)
            .stream().map(this::toPaymentDtoWithRefunds).toList();
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
            throw new BusinessException("Refund amount must be at least â‚¹1.00", HttpStatus.BAD_REQUEST);
        }

        // DB-level authoritative sum to prevent race-condition over-refunds
        BigDecimal alreadyRefunded = refundRepository.sumCompletedRefundsByPaymentId(payment.getId(), REFUNDED_STATUSES);
        BigDecimal remaining = payment.getAmount().subtract(alreadyRefunded);

        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("This payment has already been fully refunded", HttpStatus.CONFLICT);
        }

        if (request.getAmount().compareTo(remaining) > 0) {
            throw new BusinessException(
                String.format("Refund amount â‚¹%.2f exceeds remaining refundable amount â‚¹%.2f",
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

        log.info("Refund {} of â‚¹{} by {} for payment {} (booking: {})",
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
     * Admin dashboard statistics for the payment service.
     */
    @Transactional(readOnly = true)
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
            .paidAt(payment.getPaidAt())
            .build();

        kafkaTemplate.send(topic, payment.getBookingRef(), event);
        log.debug("Published {} event for booking: {}", topic, payment.getBookingRef());
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

        kafkaTemplate.send(KafkaTopics.PAYMENT_REFUNDED, payment.getBookingRef(), event);
        log.debug("Published payment.refunded event for booking: {}", payment.getBookingRef());
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
     * Creates a Razorpay order via the Razorpay Orders API and returns the order ID (e.g. "order_xxx").
     * Only called when simulation is disabled and a valid key-id is configured.
     */
    @SuppressWarnings("unchecked")
    private String createRazorpayOrder(BigDecimal amount, String currency, String receipt) {
        try {
            String credentials = Base64.getEncoder()
                .encodeToString((razorpayKeyId + ":" + razorpayKeySecret).getBytes(StandardCharsets.UTF_8));

            Map<String, Object> body = Map.of(
                "amount", amount.multiply(BigDecimal.valueOf(100)).longValue(), // rupees → paise
                "currency", currency != null ? currency : "INR",
                "receipt", receipt
            );

            Map<String, Object> response = RestClient.create()
                .post()
                .uri("https://api.razorpay.com/v1/orders")
                .header("Authorization", "Basic " + credentials)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

            if (response != null && response.containsKey("id")) {
                return (String) response.get("id");
            }
            throw new BusinessException("Razorpay order creation failed — no id returned", HttpStatus.BAD_GATEWAY);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create Razorpay order for receipt={}: {}", receipt, e.getMessage());
            throw new BusinessException("Payment gateway error. Please try again.", HttpStatus.BAD_GATEWAY);
        }
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
