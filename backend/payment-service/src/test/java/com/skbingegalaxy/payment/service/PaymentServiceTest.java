package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.payment.dto.*;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.entity.Refund;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import com.skbingegalaxy.payment.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks private PaymentService paymentService;

    private Payment testPayment;

    /** Stub both refund-enrichment calls with "no refunds yet" defaults. */
    private void stubNoRefunds(Long paymentId) {
        when(refundRepository.sumCompletedRefundsByPaymentId(eq(paymentId), anyList()))
                .thenReturn(BigDecimal.ZERO);
        when(refundRepository.countByPaymentIdAndStatusIn(eq(paymentId), anyList()))
                .thenReturn(0L);
    }

    @BeforeEach
    void setUp() {
        testPayment = Payment.builder()
                .id(1L)
                .bookingRef("SKBG25123456")
                .customerId(1L)
                .transactionId("TXN-ABCD1234")
                .gatewayOrderId("ORD-EFGH5678")
                .amount(BigDecimal.valueOf(5000))
                .paymentMethod(PaymentMethod.UPI)
                .status(PaymentStatus.INITIATED)
                .currency("INR")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // â”€â”€ Initiate payment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void initiatePayment_success() {
        InitiatePaymentRequest request = InitiatePaymentRequest.builder()
                .bookingRef("SKBG25123456")
                .amount(BigDecimal.valueOf(5000))
                .paymentMethod(PaymentMethod.UPI)
                .currency("INR")
                .build();

        when(paymentRepository.findByBookingRefAndStatus("SKBG25123456", PaymentStatus.SUCCESS))
                .thenReturn(Optional.empty());
        when(paymentRepository.findFirstByBookingRefAndStatusOrderByCreatedAtDesc(
                "SKBG25123456", PaymentStatus.INITIATED))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        stubNoRefunds(1L);

        PaymentDto result = paymentService.initiatePayment(request, 1L);

        assertThat(result.getBookingRef()).isEqualTo("SKBG25123456");
        assertThat(result.getTransactionId()).isEqualTo("TXN-ABCD1234");
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void initiatePayment_idempotentRetry_returnsExisting() {
        InitiatePaymentRequest request = InitiatePaymentRequest.builder()
                .bookingRef("SKBG25123456")
                .amount(BigDecimal.valueOf(5000))
                .paymentMethod(PaymentMethod.UPI)
                .build();

        when(paymentRepository.findByBookingRefAndStatus("SKBG25123456", PaymentStatus.SUCCESS))
                .thenReturn(Optional.empty());
        when(paymentRepository.findFirstByBookingRefAndStatusOrderByCreatedAtDesc(
                "SKBG25123456", PaymentStatus.INITIATED))
                .thenReturn(Optional.of(testPayment));
        stubNoRefunds(1L);

        PaymentDto result = paymentService.initiatePayment(request, 1L);

        assertThat(result.getTransactionId()).isEqualTo("TXN-ABCD1234");
        // Must NOT create a second record
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void initiatePayment_alreadyPaid_throwsException() {
        InitiatePaymentRequest request = InitiatePaymentRequest.builder()
                .bookingRef("SKBG25123456")
                .amount(BigDecimal.valueOf(5000))
                .paymentMethod(PaymentMethod.UPI)
                .build();

        testPayment.setStatus(PaymentStatus.SUCCESS);
        when(paymentRepository.findByBookingRefAndStatus("SKBG25123456", PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(testPayment));

        assertThatThrownBy(() -> paymentService.initiatePayment(request, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already completed");
    }

    // â”€â”€ Handle callback â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void handleCallback_success() {
        PaymentCallbackRequest request = PaymentCallbackRequest.builder()
                .gatewayOrderId("ORD-EFGH5678")
                .gatewayPaymentId("PAY-12345")
                .status("success")
                .build();

        when(paymentRepository.findByGatewayOrderId("ORD-EFGH5678"))
                .thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        stubNoRefunds(1L);

        paymentService.handleCallback(request);

        assertThat(testPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(testPayment.getGatewayPaymentId()).isEqualTo("PAY-12345");
        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    @Test
    void handleCallback_failed() {
        PaymentCallbackRequest request = PaymentCallbackRequest.builder()
                .gatewayOrderId("ORD-EFGH5678")
                .status("failed")
                .errorDescription("Insufficient funds")
                .build();

        when(paymentRepository.findByGatewayOrderId("ORD-EFGH5678"))
                .thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        stubNoRefunds(1L);

        paymentService.handleCallback(request);

        assertThat(testPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(testPayment.getFailureReason()).isEqualTo("Insufficient funds");
    }

    @Test
    void handleCallback_alreadySuccessful_returnsExisting() {
        testPayment.setStatus(PaymentStatus.SUCCESS);
        PaymentCallbackRequest request = PaymentCallbackRequest.builder()
                .gatewayOrderId("ORD-EFGH5678")
                .status("success")
                .build();

        when(paymentRepository.findByGatewayOrderId("ORD-EFGH5678"))
                .thenReturn(Optional.of(testPayment));
        stubNoRefunds(1L);

        paymentService.handleCallback(request);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void handleCallback_orderNotFound_throwsException() {
        PaymentCallbackRequest request = PaymentCallbackRequest.builder()
                .gatewayOrderId("INVALID")
                .build();

        when(paymentRepository.findByGatewayOrderId("INVALID")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.handleCallback(request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // â”€â”€ Simulate payment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void simulatePayment_success() {
        when(paymentRepository.findByTransactionId("TXN-ABCD1234"))
                .thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        stubNoRefunds(1L);

        paymentService.simulatePayment("TXN-ABCD1234");

        assertThat(testPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(testPayment.getPaidAt()).isNotNull();
        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    @Test
    void simulatePayment_alreadySuccess_idempotent() {
        testPayment.setStatus(PaymentStatus.SUCCESS);
        when(paymentRepository.findByTransactionId("TXN-ABCD1234"))
                .thenReturn(Optional.of(testPayment));
        stubNoRefunds(1L);

        paymentService.simulatePayment("TXN-ABCD1234");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void simulatePayment_refundedPayment_throwsException() {
        testPayment.setStatus(PaymentStatus.REFUNDED);
        when(paymentRepository.findByTransactionId("TXN-ABCD1234"))
                .thenReturn(Optional.of(testPayment));

        assertThatThrownBy(() -> paymentService.simulatePayment("TXN-ABCD1234"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot simulate a REFUNDED payment");
    }

    // â”€â”€ Cancel payment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void cancelPayment_success() {
        when(paymentRepository.findByTransactionId("TXN-ABCD1234"))
                .thenReturn(Optional.of(testPayment)); // INITIATED
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        stubNoRefunds(1L);

        PaymentDto result = paymentService.cancelPayment("TXN-ABCD1234", 1L);

        assertThat(testPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(testPayment.getFailureReason()).isEqualTo("Cancelled by customer");
    }

    @Test
    void cancelPayment_wrongOwner_throwsException() {
        when(paymentRepository.findByTransactionId("TXN-ABCD1234"))
                .thenReturn(Optional.of(testPayment));

        assertThatThrownBy(() -> paymentService.cancelPayment("TXN-ABCD1234", 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not authorized");
    }

    @Test
    void cancelPayment_notInitiated_throwsException() {
        testPayment.setStatus(PaymentStatus.SUCCESS);
        when(paymentRepository.findByTransactionId("TXN-ABCD1234"))
                .thenReturn(Optional.of(testPayment));

        assertThatThrownBy(() -> paymentService.cancelPayment("TXN-ABCD1234", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only INITIATED");
    }

    // â”€â”€ Initiate refund â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void initiateRefund_success() {
        testPayment.setStatus(PaymentStatus.SUCCESS);
        RefundRequest request = RefundRequest.builder()
                .paymentId(1L)
                .amount(BigDecimal.valueOf(2000))
                .reason("Customer request")
                .build();

        Refund savedRefund = Refund.builder()
                .id(1L).payment(testPayment)
                .amount(BigDecimal.valueOf(2000))
                .reason("Customer request")
                .gatewayRefundId("RFD-XYZ12345")
                .status(PaymentStatus.REFUNDED)
                .initiatedBy("admin")
                .refundedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testPayment));
        when(refundRepository.sumCompletedRefundsByPaymentId(eq(1L), anyList()))
                .thenReturn(BigDecimal.ZERO);
        when(refundRepository.save(any(Refund.class))).thenReturn(savedRefund);

        RefundDto result = paymentService.initiateRefund(request, "admin");

        assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(2000));
        assertThat(result.getGatewayRefundId()).isEqualTo("RFD-XYZ12345");
        verify(refundRepository).save(any(Refund.class));
        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    @Test
    void initiateRefund_partiallyRefunded_success() {
        testPayment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        RefundRequest request = RefundRequest.builder()
                .paymentId(1L)
                .amount(BigDecimal.valueOf(1000))
                .reason("Further refund")
                .build();

        Refund savedRefund = Refund.builder()
                .id(2L).payment(testPayment)
                .amount(BigDecimal.valueOf(1000))
                .gatewayRefundId("RFD-PARTIAL")
                .status(PaymentStatus.REFUNDED)
                .initiatedBy("admin")
                .refundedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testPayment));
        when(refundRepository.sumCompletedRefundsByPaymentId(eq(1L), anyList()))
                .thenReturn(BigDecimal.valueOf(2000)); // 2000 already refunded of 5000
        when(refundRepository.save(any(Refund.class))).thenReturn(savedRefund);

        RefundDto result = paymentService.initiateRefund(request, "admin");

        assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void initiateRefund_paymentNotEligible_throwsException() {
        RefundRequest request = RefundRequest.builder()
                .paymentId(1L)
                .amount(BigDecimal.valueOf(2000))
                .build();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testPayment)); // INITIATED

        assertThatThrownBy(() -> paymentService.initiateRefund(request, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot refund a payment with status: INITIATED");
    }

    @Test
    void initiateRefund_exceedsRemaining_throwsException() {
        testPayment.setStatus(PaymentStatus.SUCCESS);
        RefundRequest request = RefundRequest.builder()
                .paymentId(1L)
                .amount(BigDecimal.valueOf(6000)) // payment is 5000
                .build();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testPayment));
        when(refundRepository.sumCompletedRefundsByPaymentId(eq(1L), anyList()))
                .thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> paymentService.initiateRefund(request, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds remaining refundable");
    }

    @Test
    void initiateRefund_alreadyFullyRefunded_throwsConflict() {
        testPayment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        RefundRequest request = RefundRequest.builder()
                .paymentId(1L)
                .amount(BigDecimal.valueOf(1))
                .build();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testPayment));
        when(refundRepository.sumCompletedRefundsByPaymentId(eq(1L), anyList()))
                .thenReturn(BigDecimal.valueOf(5000)); // already fully refunded

        assertThatThrownBy(() -> paymentService.initiateRefund(request, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been fully refunded");
    }

    @Test
    void initiateRefund_amountTooSmall_throwsException() {
        testPayment.setStatus(PaymentStatus.SUCCESS);
        RefundRequest request = RefundRequest.builder()
                .paymentId(1L)
                .amount(BigDecimal.valueOf(0.50)) // below â‚¹1 minimum
                .build();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testPayment));
        // Note: sumCompletedRefundsByPaymentId is not stubbed because the amount check
        // fires immediately after status validation, before the sum query is reached.

        assertThatThrownBy(() -> paymentService.initiateRefund(request, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least â‚¹1.00");
    }

    // â”€â”€ Query methods â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getPaymentByTransactionId_success() {
        when(paymentRepository.findByTransactionId("TXN-ABCD1234"))
                .thenReturn(Optional.of(testPayment));
        stubNoRefunds(1L);

        PaymentDto result = paymentService.getPaymentByTransactionId("TXN-ABCD1234");
        assertThat(result.getTransactionId()).isEqualTo("TXN-ABCD1234");
    }

    @Test
    void getPaymentByTransactionId_notFound_throwsException() {
        when(paymentRepository.findByTransactionId("INVALID")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPaymentByTransactionId("INVALID"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getCustomerPayments_returnsList() {
        when(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(testPayment));
        stubNoRefunds(1L);

        List<PaymentDto> result = paymentService.getCustomerPayments(1L);
        assertThat(result).hasSize(1);
    }

    @Test
    void getPaymentsByBookingRef_returnsList() {
        when(paymentRepository.findByBookingRefOrderByCreatedAtDesc("SKBG25123456"))
                .thenReturn(List.of(testPayment));
        stubNoRefunds(1L);

        List<PaymentDto> result = paymentService.getPaymentsByBookingRef("SKBG25123456");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBookingRef()).isEqualTo("SKBG25123456");
    }

    // ── Record cash payment ─────────────────────────────────────────────────

    @Test
    void recordCashPayment_success() {
        RecordCashPaymentRequest request = RecordCashPaymentRequest.builder()
                .bookingRef("SKBG25999999")
                .amount(BigDecimal.valueOf(8000))
                .customerId(2L)
                .notes("Paid at counter")
                .build();

        when(paymentRepository.findByBookingRefAndStatus("SKBG25999999", PaymentStatus.SUCCESS))
                .thenReturn(Optional.empty());

        Payment cashPayment = Payment.builder()
                .id(10L)
                .bookingRef("SKBG25999999")
                .customerId(2L)
                .transactionId("CASH-ABC123")
                .gatewayOrderId("CASH-ORD-XYZ")
                .amount(BigDecimal.valueOf(8000))
                .paymentMethod(PaymentMethod.CASH)
                .status(PaymentStatus.SUCCESS)
                .currency("INR")
                .paidAt(LocalDateTime.now())
                .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(cashPayment);
        stubNoRefunds(10L);

        PaymentDto result = paymentService.recordCashPayment(request, "admin@test.com");

        assertThat(result.getBookingRef()).isEqualTo("SKBG25999999");
        assertThat(result.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    @Test
    void recordCashPayment_idempotent_returnsExisting() {
        RecordCashPaymentRequest request = RecordCashPaymentRequest.builder()
                .bookingRef("SKBG25999999")
                .amount(BigDecimal.valueOf(8000))
                .customerId(2L)
                .build();

        testPayment.setStatus(PaymentStatus.SUCCESS);
        when(paymentRepository.findByBookingRefAndStatus("SKBG25999999", PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(testPayment));
        stubNoRefunds(1L);

        PaymentDto result = paymentService.recordCashPayment(request, "admin@test.com");

        assertThat(result.getTransactionId()).isEqualTo("TXN-ABCD1234");
        verify(paymentRepository, never()).save(any());
    }

    // ── Add payment ─────────────────────────────────────────────────────────

    @Test
    void addPayment_success() {
        AddPaymentRequest request = AddPaymentRequest.builder()
                .bookingRef("SKBG25123456")
                .amount(BigDecimal.valueOf(3000))
                .customerId(1L)
                .paymentMethod(PaymentMethod.UPI)
                .notes("Split payment")
                .build();

        Payment additionalPayment = Payment.builder()
                .id(20L)
                .bookingRef("SKBG25123456")
                .customerId(1L)
                .transactionId("UPI-SPLIT123")
                .gatewayOrderId("ADM-ORD-SPLIT")
                .amount(BigDecimal.valueOf(3000))
                .paymentMethod(PaymentMethod.UPI)
                .status(PaymentStatus.SUCCESS)
                .currency("INR")
                .paidAt(LocalDateTime.now())
                .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(additionalPayment);
        stubNoRefunds(20L);

        PaymentDto result = paymentService.addPayment(request, "admin@test.com");

        assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(3000));
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    @Test
    void addPayment_withNullCustomerId_defaultsToZero() {
        AddPaymentRequest request = AddPaymentRequest.builder()
                .bookingRef("SKBG25123456")
                .amount(BigDecimal.valueOf(1000))
                .customerId(null)
                .paymentMethod(PaymentMethod.CASH)
                .build();

        Payment saved = Payment.builder()
                .id(21L)
                .bookingRef("SKBG25123456")
                .customerId(0L)
                .transactionId("CASH-NULL")
                .gatewayOrderId("ADM-ORD-NULL")
                .amount(BigDecimal.valueOf(1000))
                .paymentMethod(PaymentMethod.CASH)
                .status(PaymentStatus.SUCCESS)
                .currency("INR")
                .paidAt(LocalDateTime.now())
                .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(saved);
        stubNoRefunds(21L);

        PaymentDto result = paymentService.addPayment(request, "admin@test.com");

        assertThat(result.getCustomerId()).isEqualTo(0L);
    }

    // ── Get refunds for payment ─────────────────────────────────────────────

    @Test
    void getRefundsForPayment_success() {
        Refund refund = Refund.builder()
                .id(1L)
                .payment(testPayment)
                .amount(BigDecimal.valueOf(2000))
                .reason("Customer request")
                .gatewayRefundId("RFD-123")
                .status(PaymentStatus.REFUNDED)
                .initiatedBy("admin")
                .refundedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(testPayment));
        when(refundRepository.findByPaymentIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(refund));

        List<RefundDto> result = paymentService.getRefundsForPayment(1L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGatewayRefundId()).isEqualTo("RFD-123");
    }

    @Test
    void getRefundsForPayment_paymentNotFound_throwsException() {
        when(paymentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getRefundsForPayment(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Payment stats ───────────────────────────────────────────────────────

    @Test
    void getPaymentStats_returnsAllFields() {
        when(paymentRepository.getTotalSuccessfulPayments()).thenReturn(BigDecimal.valueOf(50000));
        when(refundRepository.sumAllCompletedRefunds(anyList())).thenReturn(BigDecimal.valueOf(5000));
        when(paymentRepository.countByStatus(PaymentStatus.SUCCESS)).thenReturn(10L);
        when(paymentRepository.countByStatus(PaymentStatus.FAILED)).thenReturn(2L);
        when(paymentRepository.countByStatus(PaymentStatus.INITIATED)).thenReturn(1L);
        when(paymentRepository.countByStatus(PaymentStatus.REFUNDED)).thenReturn(3L);
        when(paymentRepository.countByStatus(PaymentStatus.PARTIALLY_REFUNDED)).thenReturn(1L);

        var stats = paymentService.getPaymentStats();

        assertThat(stats.get("totalRevenue")).isEqualTo(BigDecimal.valueOf(50000));
        assertThat(stats.get("totalRefunded")).isEqualTo(BigDecimal.valueOf(5000));
        assertThat(stats.get("netRevenue")).isEqualTo(BigDecimal.valueOf(45000));
        assertThat(stats.get("successCount")).isEqualTo(10L);
        assertThat(stats.get("failedCount")).isEqualTo(2L);
        assertThat(stats.get("initiatedCount")).isEqualTo(1L);
        assertThat(stats.get("refundedCount")).isEqualTo(3L);
        assertThat(stats.get("partiallyRefundedCount")).isEqualTo(1L);
    }

    // ── Simulate payment – failed retry ─────────────────────────────────────

    @Test
    void simulatePayment_failedStatus_retries() {
        testPayment.setStatus(PaymentStatus.FAILED);
        when(paymentRepository.findByTransactionId("TXN-ABCD1234"))
                .thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        stubNoRefunds(1L);

        paymentService.simulatePayment("TXN-ABCD1234");

        assertThat(testPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    // ── handleCallback with "captured" status ───────────────────────────────

    @Test
    void handleCallback_capturedStatus_success() {
        PaymentCallbackRequest request = PaymentCallbackRequest.builder()
                .gatewayOrderId("ORD-EFGH5678")
                .gatewayPaymentId("PAY-CAP-1")
                .status("captured")
                .build();

        when(paymentRepository.findByGatewayOrderId("ORD-EFGH5678"))
                .thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        stubNoRefunds(1L);

        paymentService.handleCallback(request);

        assertThat(testPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }
}

