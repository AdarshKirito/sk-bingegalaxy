package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.payment.client.RazorpayGatewayClient;
import com.skbingegalaxy.payment.dto.PaymentCallbackRequest;
import com.skbingegalaxy.payment.dto.RefundDto;
import com.skbingegalaxy.payment.dto.RefundRequest;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.entity.Refund;
import com.skbingegalaxy.payment.entity.RefundStatus;
import com.skbingegalaxy.payment.event.PaymentKafkaEvent;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import com.skbingegalaxy.payment.repository.RefundRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PAY-002 / PAY-003 regression fence.
 *
 * <p>The original defect: every refund path fabricated a local "RFD-…" id and
 * marked the refund SUCCEEDED without any gateway call — customers were told
 * "refunded" while no money moved. These tests pin the fail-closed contract:</p>
 * <ul>
 *   <li>a gateway-backed payment is refunded ONLY through the real Razorpay
 *       refund API;</li>
 *   <li>a gateway failure yields a FAILED refund row (admin queue) and never
 *       a customer-facing "refunded" event;</li>
 *   <li>a gateway "pending" acceptance yields a PROCESSING row that settles
 *       later via webhook/reconciliation — again, no premature email;</li>
 *   <li>the late-capture callback branch cannot run without a verified
 *       signature (PAY-003).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceRefundFailClosedTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RazorpayGatewayClient razorpayGatewayClient;
    @Mock private com.skbingegalaxy.payment.client.BookingAmountClient bookingAmountClient;
    @Mock private com.skbingegalaxy.payment.repository.PaymentStatusHistoryRepository statusHistoryRepository;
    @Mock private WebhookDedupService webhookDedupService;
    @Mock private AuditLogService auditLogService;
    @Mock private PaymentMetrics metrics;
    @Mock private AdminApprovalService approvalService;

    @InjectMocks private PaymentService paymentService;

    private Payment gatewayPayment;

    @BeforeEach
    void setUp() {
        BingeContext.clear();
        ReflectionTestUtils.setField(paymentService, "razorpayKeySecret", "test-secret");
        ReflectionTestUtils.setField(paymentService, "self", paymentService);
        // A REAL gateway-captured payment: order_/pay_ prefixed ids.
        gatewayPayment = Payment.builder()
                .id(9L)
                .bookingRef("SKBG26999999")
                .customerId(5L)
                .transactionId("TXN-REAL00001")
                .gatewayOrderId("order_LiveAbc12345")
                .gatewayPaymentId("pay_LiveXyz67890")
                .amount(BigDecimal.valueOf(4000))
                .paymentMethod(PaymentMethod.UPI)
                .status(PaymentStatus.SUCCESS)
                .currency("INR")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    private RefundRequest refundOf(long amount) {
        return RefundRequest.builder()
                .paymentId(9L)
                .amount(BigDecimal.valueOf(amount))
                .reason("Customer request")
                .build();
    }

    /**
     * PAY-006 note: the refund now runs reserve-intent → provider leg →
     * finalize, so the mocks are stateful — saves assign an id and later
     * {@code findWithPaymentById} lookups return the same evolving row.
     */
    private final java.util.Map<Long, Refund> refundRows = new java.util.HashMap<>();

    private void stubIntentPersistence(long idSeed) {
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> {
            Refund r = i.getArgument(0);
            if (r.getId() == null) r.setId(idSeed);
            refundRows.put(r.getId(), r);
            return r;
        });
        when(refundRepository.findWithPaymentById(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(refundRows.get((Long) i.getArgument(0))));
    }

    private void stubGuards() {
        when(paymentRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(gatewayPayment));
        when(refundRepository.sumByPaymentIdAndRefundStatusIn(eq(9L), anyList()))
                .thenReturn(BigDecimal.ZERO);
        stubIntentPersistence(100L);
    }

    @Test
    @DisplayName("gateway error → FAILED row in the admin queue, NO refunded event, payment stays SUCCESS")
    void gatewayFailure_failsClosed() {
        stubGuards();
        when(razorpayGatewayClient.createRefund(eq("pay_LiveXyz67890"), any(), eq("INR"), anyString()))
                .thenThrow(new BusinessException("Razorpay unavailable",
                        org.springframework.http.HttpStatus.BAD_GATEWAY));

        RefundDto result = paymentService.initiateRefund(refundOf(1000), "admin@x.com");

        assertThat(result.getRefundStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.getGatewayRefundId()).isNull();
        assertThat(gatewayPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        // The customer must NOT receive a "refunded" email for money that never moved.
        verify(eventPublisher, never()).publishEvent(any(PaymentKafkaEvent.class));
    }

    @Test
    @DisplayName("gateway accepts async → PROCESSING row with the REAL rfnd_ id, no premature event")
    void gatewayPending_recordsProcessing() {
        stubGuards();
        when(razorpayGatewayClient.createRefund(eq("pay_LiveXyz67890"), any(), eq("INR"), anyString()))
                .thenReturn(new RazorpayGatewayClient.GatewayRefundResult("rfnd_Pending001", "pending"));

        RefundDto result = paymentService.initiateRefund(refundOf(1000), "admin@x.com");

        assertThat(result.getRefundStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(result.getGatewayRefundId()).isEqualTo("rfnd_Pending001");
        assertThat(gatewayPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(eventPublisher, never()).publishEvent(any(PaymentKafkaEvent.class));
    }

    @Test
    @DisplayName("gateway processed → SUCCEEDED row, payment settles, refunded event published")
    void gatewayProcessed_settlesImmediately() {
        stubGuards();
        when(refundRepository.sumCompletedRefundsByPaymentId(eq(9L), anyList()))
                .thenReturn(BigDecimal.valueOf(4000)); // full amount settled
        when(razorpayGatewayClient.createRefund(eq("pay_LiveXyz67890"), any(), eq("INR"), anyString()))
                .thenReturn(new RazorpayGatewayClient.GatewayRefundResult("rfnd_Done001", "processed"));

        RefundDto result = paymentService.initiateRefund(refundOf(4000), "admin@x.com");

        assertThat(result.getRefundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(result.getGatewayRefundId()).isEqualTo("rfnd_Done001");
        assertThat(gatewayPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(eventPublisher).publishEvent(any(PaymentKafkaEvent.class));
    }

    @Test
    @DisplayName("webhook settle: PROCESSING → SUCCEEDED, payment recomputed, event published")
    void settleFromGateway_processed() {
        Refund processing = Refund.builder()
                .id(3L).payment(gatewayPayment)
                .amount(BigDecimal.valueOf(4000))
                .gatewayRefundId("rfnd_Pending001")
                .status(PaymentStatus.INITIATED)
                .refundStatus(RefundStatus.PROCESSING)
                .build();
        when(refundRepository.findByGatewayRefundId("rfnd_Pending001")).thenReturn(Optional.of(processing));
        when(refundRepository.findById(3L)).thenReturn(Optional.of(processing));
        when(paymentRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(gatewayPayment));
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));
        when(refundRepository.sumCompletedRefundsByPaymentId(eq(9L), anyList()))
                .thenReturn(BigDecimal.valueOf(4000));

        String outcome = paymentService.settleRefundFromGateway("rfnd_Pending001", "processed", "razorpay_webhook");

        assertThat(outcome).isEqualTo("refund_settled");
        assertThat(processing.getRefundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(gatewayPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(eventPublisher).publishEvent(any(PaymentKafkaEvent.class));
    }

    @Test
    @DisplayName("webhook settle is idempotent — an already-settled refund is untouched")
    void settleFromGateway_alreadySettled() {
        Refund done = Refund.builder()
                .id(4L).payment(gatewayPayment)
                .amount(BigDecimal.valueOf(4000))
                .gatewayRefundId("rfnd_Done001")
                .status(PaymentStatus.REFUNDED)
                .refundStatus(RefundStatus.SUCCEEDED)
                .build();
        when(refundRepository.findByGatewayRefundId("rfnd_Done001")).thenReturn(Optional.of(done));
        when(refundRepository.findById(4L)).thenReturn(Optional.of(done));
        when(paymentRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(gatewayPayment));

        String outcome = paymentService.settleRefundFromGateway("rfnd_Done001", "processed", "reconciliation");

        assertThat(outcome).startsWith("already_settled");
        verify(refundRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("PAY-003: late-capture branch is unreachable without a signature — forged callback rejected")
    void lateCapture_requiresSignature() {
        // A payment cancelled with its booking — the exact precondition the
        // late-capture auto-refund branch keys on.
        gatewayPayment.setStatus(PaymentStatus.FAILED);
        gatewayPayment.setFailureReason("Booking cancelled");

        PaymentCallbackRequest forged = PaymentCallbackRequest.builder()
                .gatewayOrderId("order_LiveAbc12345")
                .gatewayPaymentId("pay_Forged00001")
                .status("success")
                .build(); // no gatewaySignature

        when(webhookDedupService.razorpayEventId(any(), any(), any())).thenReturn("evt-1");
        when(webhookDedupService.isDuplicate("evt-1")).thenReturn(false);
        when(paymentRepository.findByGatewayOrderIdForUpdate("order_LiveAbc12345"))
                .thenReturn(Optional.of(gatewayPayment));

        assertThatThrownBy(() -> paymentService.handleCallback(forged))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("signature is required");

        // No state change, no auto-refund, no gateway call.
        assertThat(gatewayPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
        verifyNoInteractions(razorpayGatewayClient);
    }

    @Test
    @DisplayName("over-refund guard counts in-flight PROCESSING attempts, not just settled ones")
    void overRefundGuard_countsInFlight() {
        when(paymentRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(gatewayPayment));
        // 3500 already claimed by settled + in-flight refunds of a 4000 payment.
        when(refundRepository.sumByPaymentIdAndRefundStatusIn(eq(9L), anyList()))
                .thenReturn(BigDecimal.valueOf(3500));

        assertThatThrownBy(() -> paymentService.initiateRefund(refundOf(1000), "admin@x.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds remaining refundable");
        verifyNoInteractions(razorpayGatewayClient);
    }

    @Test
    @DisplayName("cash payments (no gateway leg) settle locally as book-keeping")
    void cashPayment_localSettle() {
        Payment cash = Payment.builder()
                .id(11L)
                .bookingRef("SKBG26888888")
                .customerId(5L)
                .transactionId("CASH-ABC")
                .gatewayOrderId("CASH-ORD-1234")
                .amount(BigDecimal.valueOf(2000))
                .paymentMethod(PaymentMethod.CASH)
                .status(PaymentStatus.SUCCESS)
                .currency("INR")
                .createdAt(LocalDateTime.now())
                .build();
        when(paymentRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(cash));
        when(refundRepository.sumByPaymentIdAndRefundStatusIn(eq(11L), anyList()))
                .thenReturn(BigDecimal.ZERO);
        when(refundRepository.sumCompletedRefundsByPaymentId(eq(11L), anyList()))
                .thenReturn(BigDecimal.valueOf(2000));
        stubIntentPersistence(200L);

        RefundDto result = paymentService.initiateRefund(RefundRequest.builder()
                .paymentId(11L).amount(BigDecimal.valueOf(2000)).reason("Walk-in refund").build(),
                "admin@x.com");

        assertThat(result.getRefundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(result.getGatewayRefundId()).startsWith("RFD-LOCAL-");
        // Cash is returned by hand at the venue — no gateway call must happen.
        verifyNoInteractions(razorpayGatewayClient);
        // Multiple saves now (intent + receipt + finalize) — assert the FINAL state.
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository, atLeast(2)).save(saved.capture());
        assertThat(saved.getValue().getGatewayResponse()).contains("Local settle");
    }
}
