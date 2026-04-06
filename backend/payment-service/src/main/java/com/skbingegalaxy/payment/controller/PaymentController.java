package com.skbingegalaxy.payment.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.payment.dto.*;
import com.skbingegalaxy.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<PaymentDto>> initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        PaymentDto payment = paymentService.initiatePayment(request, userId);
        return ResponseEntity.ok(ApiResponse.ok("Payment initiated", payment));
    }

    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<PaymentDto>> handleCallback(
            @Valid @RequestBody PaymentCallbackRequest request) {
        PaymentDto payment = paymentService.handleCallback(request);
        return ResponseEntity.ok(ApiResponse.ok("Payment callback processed", payment));
    }

    @PostMapping("/simulate/{transactionId}")
    public ResponseEntity<ApiResponse<PaymentDto>> simulatePayment(
            @PathVariable String transactionId) {
        PaymentDto payment = paymentService.simulatePayment(transactionId);
        return ResponseEntity.ok(ApiResponse.ok("Payment simulated", payment));
    }

    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<ApiResponse<PaymentDto>> getByTransactionId(
            @PathVariable String transactionId) {
        PaymentDto payment = paymentService.getPaymentByTransactionId(transactionId);
        return ResponseEntity.ok(ApiResponse.ok("Payment details", payment));
    }

    @GetMapping("/booking/{bookingRef}")
    public ResponseEntity<ApiResponse<List<PaymentDto>>> getByBookingRef(
            @PathVariable String bookingRef) {
        List<PaymentDto> payments = paymentService.getPaymentsByBookingRef(bookingRef);
        return ResponseEntity.ok(ApiResponse.ok("Payments for booking", payments));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<PaymentDto>>> getMyPayments(
            @RequestHeader("X-User-Id") Long userId) {
        List<PaymentDto> payments = paymentService.getCustomerPayments(userId);
        return ResponseEntity.ok(ApiResponse.ok("Your payments", payments));
    }

    @PostMapping("/admin/refund")
    public ResponseEntity<ApiResponse<RefundDto>> initiateRefund(
            @Valid @RequestBody RefundRequest request,
            @RequestHeader("X-User-Email") String adminEmail) {
        RefundDto refund = paymentService.initiateRefund(request, adminEmail);
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
            @RequestHeader("X-User-Id") Long userId) {
        PaymentDto payment = paymentService.cancelPayment(transactionId, userId);
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
            @RequestHeader("X-User-Email") String adminEmail) {
        PaymentDto payment = paymentService.recordCashPayment(request, adminEmail);
        return ResponseEntity.ok(ApiResponse.ok("Cash payment recorded", payment));
    }

    /**
     * Record an additional payment for a booking with any payment method.
     * Used for split payments, method changes, or collecting remaining balances.
     */
    @PostMapping("/admin/add-payment")
    public ResponseEntity<ApiResponse<PaymentDto>> addPayment(
            @Valid @RequestBody AddPaymentRequest request,
            @RequestHeader("X-User-Email") String adminEmail) {
        PaymentDto payment = paymentService.addPayment(request, adminEmail);
        return ResponseEntity.ok(ApiResponse.ok("Payment recorded", payment));
    }
}
