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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentDto payment = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/payments/initiate", userId, request, PaymentDto.class,
            () -> paymentService.initiatePayment(request, userId, userEmail, userName));
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