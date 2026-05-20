package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingEventType;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.service.BookingEventLogService;
import com.skbingegalaxy.booking.service.statemachine.BookingStateMachine;
import com.skbingegalaxy.common.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the no-show sweeper:
 * <ul>
 *   <li>marks PENDING/CONFIRMED bookings past start+grace as NO_SHOW + audit row,</li>
 *   <li>skips bookings already CHECKED_IN (race),</li>
 *   <li>does nothing when disabled by config.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NoShowAutomationSchedulerTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingEventLogService eventLogService;
    // Mocked to satisfy @InjectMocks; replaced below with a real instance
    // wired against the same mocks so we can assert the SM-internal calls
    // (bookingRepository.save + eventLogService.logEventFull).
    @Mock private BookingStateMachine stateMachineMock;

    @InjectMocks private NoShowAutomationScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "graceMinutes", 30);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        // Swap the mocked SM for a real one wired to the mocked deps.
        ReflectionTestUtils.setField(scheduler, "stateMachine",
            new BookingStateMachine(bookingRepository, eventLogService));
    }

    @Test
    void sweep_marksCandidateBookingsAsNoShow_andWritesAudit() {
        Booking b = booking(1L, "REF1", BookingStatus.CONFIRMED, false);
        when(bookingRepository.findNoShowCandidates(any(LocalDate.class), any(LocalTime.class)))
            .thenReturn(List.of(b));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.sweep();

        ArgumentCaptor<Booking> savedBooking = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(savedBooking.capture());
        assertThat(savedBooking.getValue().getStatus()).isEqualTo(BookingStatus.NO_SHOW);

        // SM emits the audit row; description carries the SYSTEM transition
        // narrative including the sweeper's reason.
        verify(eventLogService).logEventFull(any(Booking.class),
            eq(BookingEventType.NO_SHOW), eq("CONFIRMED"),
            eq(null), eq("SYSTEM"), eq(null),
            contains("CONFIRMED → NO_SHOW"), any(), any(), any());
    }

    @Test
    void sweep_skipsAlreadyCheckedInBookings_race() {
        Booking b = booking(1L, "REF1", BookingStatus.CONFIRMED, true); // already checked in
        when(bookingRepository.findNoShowCandidates(any(LocalDate.class), any(LocalTime.class)))
            .thenReturn(List.of(b));

        scheduler.sweep();

        verify(bookingRepository, never()).save(any());
        verify(eventLogService, never()).logEventFull(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sweep_disabledByConfig_doesNothing() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.sweep();

        verify(bookingRepository, never()).findNoShowCandidates(any(), any());
        verify(bookingRepository, never()).save(any());
    }

    private static Booking booking(Long id, String ref, BookingStatus status, boolean checkedIn) {
        Booking b = new Booking();
        b.setId(id);
        b.setBookingRef(ref);
        b.setStatus(status);
        b.setCheckedIn(checkedIn);
        b.setBookingDate(LocalDate.now());
        b.setStartTime(LocalTime.of(10, 0));
        return b;
    }
}
