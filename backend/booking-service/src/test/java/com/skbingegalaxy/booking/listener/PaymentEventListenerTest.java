package com.skbingegalaxy.booking.listener;

import com.skbingegalaxy.booking.config.AdminEventBus;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.ProcessedEventRepository;
import com.skbingegalaxy.booking.service.BookingEventLogService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.SagaOrchestrator;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.event.PaymentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    @Mock private BookingService bookingService;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private SagaOrchestrator sagaOrchestrator;
    @Mock private AdminEventBus adminEventBus;
    @Mock private BookingEventLogService eventLogService;
    @Mock private com.skbingegalaxy.booking.service.BookingAnalyticsMetrics analyticsMetrics;

    @InjectMocks private PaymentEventListener paymentEventListener;

    @Test
    void onPaymentFailed_doesNotMarkProcessedWhenCompensationFails() {
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef("SKBG25123456")
            .amount(BigDecimal.valueOf(5000))
            .build();
        Booking booking = Booking.builder()
            .bookingRef("SKBG25123456")
            .status(BookingStatus.PENDING)
            .build();

        when(processedEventRepository.existsByEventKey(any())).thenReturn(false);
        when(bookingService.getBookingEntityForSystem("SKBG25123456")).thenReturn(booking);
        doThrow(new RuntimeException("database unavailable"))
            .when(bookingService)
            .cancelBookingForSystem("SKBG25123456", "Booking auto-cancelled after payment failure");

        assertThatThrownBy(() -> paymentEventListener.onPaymentFailed(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SKBG25123456");

        verify(sagaOrchestrator).markFailed(eq("SKBG25123456"), contains("database unavailable"));
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void onPaymentSuccess_readsBookingThroughSystemPath() {
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef("SKBG25123456")
            .transactionId("TXN-ABCD1234")
            .amount(BigDecimal.valueOf(5000))
            .paymentMethod("UPI")
            .build();
        Booking booking = Booking.builder()
            .bookingRef("SKBG25123456")
            .totalAmount(BigDecimal.valueOf(5000))
            .collectedAmount(BigDecimal.valueOf(5000))
            .status(BookingStatus.PENDING)
            .build();

        when(processedEventRepository.existsByEventKey(any())).thenReturn(false);
        when(bookingService.getBookingEntityForSystem("SKBG25123456")).thenReturn(booking);

        paymentEventListener.onPaymentSuccess(event);

        verify(bookingService).addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(5000));
        // The system-path read happens three times: pre-check for terminal
        // status, post-write re-read for status decisioning, and a final
        // refresh for the timeline audit row. All three must use
        // getBookingEntityForSystem so the binge-scope guard is bypassed for
        // the saga consumer.
        verify(bookingService, org.mockito.Mockito.times(3)).getBookingEntityForSystem("SKBG25123456");
        verify(bookingService).updatePaymentStatus("SKBG25123456", com.skbingegalaxy.common.enums.PaymentStatus.SUCCESS, "UPI");
        // Full payment must drive the saga all the way to CONFIRMED (not leave it stuck at
        // PAYMENT_RECEIVED) so its lifecycle tracks the booking and the underpayment guard runs.
        verify(sagaOrchestrator).advanceTo("SKBG25123456",
            com.skbingegalaxy.booking.entity.SagaState.SagaStatus.PAYMENT_RECEIVED, "PAYMENT_SUCCESS");
        verify(sagaOrchestrator).advanceTo("SKBG25123456",
            com.skbingegalaxy.booking.entity.SagaState.SagaStatus.CONFIRMED, "BOOKING_CONFIRMED");
    }

    @Test
    void onPaymentRefunded_recomputesToSuccessWhenNetCollectedStillCoversTotal() {
        // "Change payment method" fires a full refund of the old payment AND an
        // immediate re-collection under the new method. If the re-collection's
        // payment.success was already applied (out-of-order across topics), the net
        // collected still equals the total — so the booking must remain SUCCESS, not
        // be flipped to REFUNDED off the single payment's refund flag (the old bug
        // that left a fully-paid booking showing "fully refunded, ₹0 collected").
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef("SKBG25777001")
            .refundId("RF-1")
            .refundAmount(BigDecimal.valueOf(5198))
            .status("REFUNDED")
            .build();
        Booking netBooking = Booking.builder()
            .bookingRef("SKBG25777001")
            .totalAmount(BigDecimal.valueOf(5198))
            .collectedAmount(BigDecimal.valueOf(5198))
            .status(BookingStatus.CHECKED_IN)
            .build();

        when(processedEventRepository.existsByEventKey(any())).thenReturn(false);
        when(bookingService.getBookingEntityForSystem("SKBG25777001")).thenReturn(netBooking);

        paymentEventListener.onPaymentRefunded(event);

        verify(bookingService).subtractFromCollectedAmount("SKBG25777001", BigDecimal.valueOf(5198));
        verify(bookingService).updatePaymentStatus("SKBG25777001",
            com.skbingegalaxy.common.enums.PaymentStatus.SUCCESS, null);
        verify(bookingService, never()).updatePaymentStatus("SKBG25777001",
            com.skbingegalaxy.common.enums.PaymentStatus.REFUNDED, null);
    }

    @Test
    void onPaymentRefunded_setsRefundedWhenNothingLeftCollected() {
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef("SKBG25777002")
            .refundId("RF-2")
            .refundAmount(BigDecimal.valueOf(5198))
            .status("REFUNDED")
            .build();
        Booking netBooking = Booking.builder()
            .bookingRef("SKBG25777002")
            .totalAmount(BigDecimal.valueOf(5198))
            .collectedAmount(BigDecimal.ZERO)
            .status(BookingStatus.CONFIRMED)
            .build();

        when(processedEventRepository.existsByEventKey(any())).thenReturn(false);
        when(bookingService.getBookingEntityForSystem("SKBG25777002")).thenReturn(netBooking);

        paymentEventListener.onPaymentRefunded(event);

        verify(bookingService).updatePaymentStatus("SKBG25777002",
            com.skbingegalaxy.common.enums.PaymentStatus.REFUNDED, null);
    }

    @Test
    void onPaymentRefunded_setsPartiallyRefundedWhenSomeCollectionRetained() {
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef("SKBG25777003")
            .refundId("RF-3")
            .refundAmount(BigDecimal.valueOf(2000))
            .status("REFUNDED")
            .build();
        Booking netBooking = Booking.builder()
            .bookingRef("SKBG25777003")
            .totalAmount(BigDecimal.valueOf(5198))
            .collectedAmount(BigDecimal.valueOf(3198))
            .status(BookingStatus.CONFIRMED)
            .build();

        when(processedEventRepository.existsByEventKey(any())).thenReturn(false);
        when(bookingService.getBookingEntityForSystem("SKBG25777003")).thenReturn(netBooking);

        paymentEventListener.onPaymentRefunded(event);

        verify(bookingService).updatePaymentStatus("SKBG25777003",
            com.skbingegalaxy.common.enums.PaymentStatus.PARTIALLY_REFUNDED, null);
    }
}