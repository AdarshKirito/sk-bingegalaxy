package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.SagaState;
import com.skbingegalaxy.booking.entity.SagaState.SagaStatus;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.SagaStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SagaOrchestratorTest {

    @Mock private SagaStateRepository sagaStateRepository;
    @Mock private BookingRepository bookingRepository;

    @InjectMocks private SagaOrchestrator sagaOrchestrator;

    @Test
    @DisplayName("advanceTo(CONFIRMED) moves saga to COMPENSATING when booking is underpaid")
    void advanceToConfirmed_underpaid_marksCompensating() {
        SagaState saga = SagaState.builder()
            .bookingRef("SKBG001")
            .sagaStatus(SagaStatus.PAYMENT_RECEIVED)
            .lastCompletedStep("PAYMENT_SUCCESS")
            .build();
        Booking booking = Booking.builder()
            .bookingRef("SKBG001")
            .collectedAmount(BigDecimal.valueOf(1000))
            .totalAmount(BigDecimal.valueOf(5000))
            .build();

        when(sagaStateRepository.findByBookingRef("SKBG001")).thenReturn(Optional.of(saga));
        when(bookingRepository.findByBookingRef("SKBG001")).thenReturn(Optional.of(booking));

        sagaOrchestrator.advanceTo("SKBG001", SagaStatus.CONFIRMED, "BOOKING_CONFIRMED");

        assertThat(saga.getSagaStatus()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(saga.getFailureReason()).contains("Underpayment");
        assertThat(saga.getCompensationAttempts()).isEqualTo(1);
        assertThat(saga.getLastCompletedStep()).isEqualTo("PAYMENT_SUCCESS");
        verify(sagaStateRepository).save(saga);
    }

    @Test
    @DisplayName("advanceTo(CONFIRMED) succeeds when booking is fully paid")
    void advanceToConfirmed_fullyPaid_advancesSaga() {
        SagaState saga = SagaState.builder()
            .bookingRef("SKBG002")
            .sagaStatus(SagaStatus.PAYMENT_RECEIVED)
            .lastCompletedStep("PAYMENT_SUCCESS")
            .build();
        Booking booking = Booking.builder()
            .bookingRef("SKBG002")
            .collectedAmount(BigDecimal.valueOf(5000))
            .totalAmount(BigDecimal.valueOf(5000))
            .build();

        when(sagaStateRepository.findByBookingRef("SKBG002")).thenReturn(Optional.of(saga));
        when(bookingRepository.findByBookingRef("SKBG002")).thenReturn(Optional.of(booking));

        sagaOrchestrator.advanceTo("SKBG002", SagaStatus.CONFIRMED, "BOOKING_CONFIRMED");

        assertThat(saga.getSagaStatus()).isEqualTo(SagaStatus.CONFIRMED);
        assertThat(saga.getLastCompletedStep()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(saga.getFailureReason()).isNull();
        verify(sagaStateRepository).save(saga);
    }
}