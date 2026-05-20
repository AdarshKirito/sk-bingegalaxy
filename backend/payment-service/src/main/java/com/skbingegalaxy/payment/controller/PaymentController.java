package com.skbingegalaxy.payment.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.payment.service.PaymentBingeScopeService;
import com.skbingegalaxy.payment.service.IdempotencyService;
import com.skbingegalaxy.payment.dto.*;
import com.skbingegalaxy.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Validated
public class PaymentController {

    private final PaymentBingeScopeService scopeService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    @ModelAttribute
    void validateBingeScope(
            HttpServletRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        String uri = request.getRequestURI();
        if (uri.endsWith("/callback")) {
            return;
        }
        if (uri.contains("/admin/")) {
            scopeService.requireManagedBinge(userId, role, "managing payments");
            return;
        }
        scopeService.requireSelectedBinge("accessing payments");
    }

    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<PaymentDto>> initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Name", required = false) String userName,
            @RequestHeader(value = "X-User-Phone", required = false) String userPhone,
            @RequestHeader(value = "X-User-Phone-Country-Code", required = false) String userPhoneCountryCode,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentDto payment = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/payments/initiate", userId, request, PaymentDto.class,
            () -> paymentService.initiatePayment(request, userId, userEmail, userName, userPhone, userPhoneCountryCode));
        return ResponseEntity.ok(ApiResponse.ok("Payment initiated", payment));
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
        return ResponseEntity.ok(ApiResponse.ok("Refund initiated", refund));
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
     * so the UI can render a status timeline. Tenancy-scoped.
     */
    @GetMapping("/booking/{bookingRef}/refunds")
    public ResponseEntity<ApiResponse<List<RefundDto>>> getRefundsForBooking(
            @PathVariable String bookingRef) {
        return ResponseEntity.ok(ApiResponse.ok(
            "Refund timeline for booking",
            paymentService.getRefundsForBooking(bookingRef)));
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