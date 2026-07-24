package com.skbingegalaxy.payment.listener;

import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.BookingEvent;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.entity.Refund;
import com.skbingegalaxy.payment.entity.RefundStatus;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import com.skbingegalaxy.payment.repository.RefundRepository;
import com.skbingegalaxy.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOOK-004 saga participant tests: a booking.cancelled event carrying a
 * policy refund amount reserves REAL refund intents against the booking's
 * captured payments, idempotently across redeliveries.
 */
@ExtendWith(MockitoExtension.class)
class BookingCancelledEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private PaymentService paymentService;

    @InjectMocks private BookingCancelledEventListener listener;

    private Payment captured;

    @BeforeEach
    void setUp() {
        captured = Payment.builder()
            .id(9L)
            .bookingRef("SKBG26777777")
            .customerId(5L)
            .transactionId("TXN-CAP1")
            .gatewayOrderId("order_Abc")
            .gatewayPaymentId("pay_Abc")
            .amount(BigDecimal.valueOf(4000))
            .paymentMethod(PaymentMethod.UPI)
            .status(PaymentStatus.SUCCESS)
            .currency("INR")
            .createdAt(LocalDateTime.now())
            .build();
        when(paymentRepository.findByBookingRefAndStatus("SKBG26777777", PaymentStatus.INITIATED))
            .thenReturn(List.of());
    }

    private BookingEvent cancelledEvent(BigDecimal refundAmount) {
        return BookingEvent.builder()
            .bookingRef("SKBG26777777")
            .bingeId(11L)
            .customerId(5L)
            .refundAmount(refundAmount)
            .build();
    }

    @Test
    @DisplayName("refundAmount > 0 → refund intent reserved against the captured payment")
    void refundOwed_reservesIntent() {
        when(refundRepository.findByBookingRefOrderByCreatedAtDesc("SKBG26777777"))
            .thenReturn(List.of());
        when(paymentRepository.findByBookingRefAndStatus("SKBG26777777", PaymentStatus.SUCCESS))
            .thenReturn(List.of(captured));
        when(paymentRepository.findByBookingRefAndStatus("SKBG26777777", PaymentStatus.PARTIALLY_REFUNDED))
            .thenReturn(List.of());
        when(refundRepository.sumByPaymentIdAndRefundStatusIn(eq(9L), anyList()))
            .thenReturn(BigDecimal.ZERO);
        when(paymentService.reserveRefundIntent(eq(9L), eq(BigDecimal.valueOf(2000)),
            eq(BookingCancelledEventListener.CANCELLATION_REFUND_REASON), eq("SYSTEM"), eq(null), eq(false)))
            .thenReturn(77L);

        listener.onBookingCancelled(cancelledEvent(BigDecimal.valueOf(2000)));

        verify(paymentService).reserveRefundIntent(eq(9L), eq(BigDecimal.valueOf(2000)),
            eq(BookingCancelledEventListener.CANCELLATION_REFUND_REASON), eq("SYSTEM"), eq(null), eq(false));
    }

    @Test
    @DisplayName("redelivery after the refund was reserved → idempotent skip, no second intent")
    void redelivery_isIdempotent() {
        Refund alreadyReserved = Refund.builder()
            .id(77L)
            .payment(captured)
            .amount(BigDecimal.valueOf(2000))
            .reason(BookingCancelledEventListener.CANCELLATION_REFUND_REASON)
            .refundStatus(RefundStatus.INITIATED)
            .status(PaymentStatus.INITIATED)
            .build();
        when(refundRepository.findByBookingRefOrderByCreatedAtDesc("SKBG26777777"))
            .thenReturn(List.of(alreadyReserved));

        listener.onBookingCancelled(cancelledEvent(BigDecimal.valueOf(2000)));

        verify(paymentService, never()).reserveRefundIntent(any(), any(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("no refund owed (unpaid / 0% tier) → only INITIATED cleanup, no refunds")
    void noRefundOwed_skipsRefunds() {
        listener.onBookingCancelled(cancelledEvent(BigDecimal.ZERO));

        verify(paymentService, never()).reserveRefundIntent(any(), any(), any(), any(), any(), any(Boolean.class));
        verify(refundRepository, never()).findByBookingRefOrderByCreatedAtDesc(any());
    }
}
