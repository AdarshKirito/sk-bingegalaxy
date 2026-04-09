package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingBookingTimeoutSchedulerTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingService bookingService;

    @InjectMocks private PendingBookingTimeoutScheduler scheduler;

    @Test
    void cancelStalePendingBookings_usesSystemCancellationPath() {
        ReflectionTestUtils.setField(scheduler, "pendingTimeoutMinutes", 30);

        Booking staleBooking = Booking.builder()
            .bookingRef("SKBG25123456")
            .createdAt(LocalDateTime.now().minusMinutes(45))
            .build();
        when(bookingRepository.findStalePendingBookings(any())).thenReturn(List.of(staleBooking));

        scheduler.cancelStalePendingBookings();

        verify(bookingService).cancelBookingForSystem(
            "SKBG25123456",
            "Booking auto-cancelled after payment timeout");
        verify(bookingService, never()).cancelBooking("SKBG25123456");
    }
}