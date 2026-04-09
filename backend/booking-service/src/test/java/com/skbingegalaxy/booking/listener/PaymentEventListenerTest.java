package com.skbingegalaxy.booking.listener;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.ProcessedEventRepository;
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
        verify(bookingService).getBookingEntityForSystem("SKBG25123456");
        verify(bookingService).updatePaymentStatus("SKBG25123456", com.skbingegalaxy.common.enums.PaymentStatus.SUCCESS, "UPI");
    }
}