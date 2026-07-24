package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingEventType;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.service.BookingEventLogService;
import com.skbingegalaxy.booking.service.VenueClockService;
import com.skbingegalaxy.booking.service.statemachine.BookingStateMachine;
import com.skbingegalaxy.common.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
 *   <li>marks PENDING/CONFIRMED bookings past their <b>midpoint</b> as NO_SHOW + audit row,</li>
 *   <li>does NOT mark a booking that has not yet reached its midpoint,</li>
 *   <li>skips bookings already CHECKED_IN (race),</li>
 *   <li>does nothing when disabled by config.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoShowAutomationSchedulerTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingEventLogService eventLogService;
    // Mocked to satisfy @InjectMocks; replaced below with a real instance
    // wired against the same mocks so we can assert the SM-internal calls
    // (bookingRepository.save + eventLogService.logEventFull).
    @Mock private BookingStateMachine stateMachineMock;
    @Mock private VenueClockService venueClock;

    @InjectMocks private NoShowAutomationScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "minGraceMinutes", 0);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        // Every booking resolves to a real, fixed zone so LocalDateTime.now(zone)
        // inside the sweeper is a concrete instant we can reason about.
        when(venueClock.zoneOf(any())).thenReturn(ZoneId.of("UTC"));
        // Swap the mocked SM for a real one wired to the mocked deps.
        ReflectionTestUtils.setField(scheduler, "stateMachine",
            new BookingStateMachine(bookingRepository, eventLogService));
    }

    @Test
    void sweep_marksBookingsPastMidpointAsNoShow_andWritesAudit() {
        // Yesterday 10:00 for 60 min → midpoint 10:30, firmly in the past.
        Booking b = booking(1L, "REF1", BookingStatus.CONFIRMED, false,
            LocalDate.now(ZoneId.of("UTC")).minusDays(1), LocalTime.of(10, 0), 60);
        when(bookingRepository.findNoShowSweepCandidates(any(LocalDate.class), any(LocalDate.class)))
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
    void sweep_skipsBookingThatHasNotReachedMidpoint() {
        // Today, far in the future (23:59) for 60 min → midpoint not yet reached.
        Booking b = booking(1L, "REF1", BookingStatus.CONFIRMED, false,
            LocalDate.now(ZoneId.of("UTC")), LocalTime.of(23, 59), 60);
        when(bookingRepository.findNoShowSweepCandidates(any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of(b));

        scheduler.sweep();

        verify(bookingRepository, never()).save(any());
    }

    @Test
    void sweep_skipsAlreadyCheckedInBookings_race() {
        Booking b = booking(1L, "REF1", BookingStatus.CONFIRMED, true,
            LocalDate.now(ZoneId.of("UTC")).minusDays(1), LocalTime.of(10, 0), 60); // already checked in
        when(bookingRepository.findNoShowSweepCandidates(any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of(b));

        scheduler.sweep();

        verify(bookingRepository, never()).save(any());
        verify(eventLogService, never()).logEventFull(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sweep_disabledByConfig_doesNothing() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.sweep();

        verify(bookingRepository, never()).findNoShowSweepCandidates(any(), any());
        verify(bookingRepository, never()).save(any());
    }

    private static Booking booking(Long id, String ref, BookingStatus status, boolean checkedIn,
                                   LocalDate date, LocalTime start, int durationMinutes) {
        Booking b = new Booking();
        b.setId(id);
        b.setBookingRef(ref);
        b.setStatus(status);
        b.setCheckedIn(checkedIn);
        b.setBookingDate(date);
        b.setStartTime(start);
        b.setDurationMinutes(durationMinutes);
        return b;
    }
}
