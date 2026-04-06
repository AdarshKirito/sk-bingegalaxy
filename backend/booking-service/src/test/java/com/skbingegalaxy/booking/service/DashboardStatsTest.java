package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.client.AvailabilityClientFallback;
import com.skbingegalaxy.booking.dto.AuditResultDto;
import com.skbingegalaxy.booking.dto.DashboardStatsDto;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for getDashboardStats — verifies all counts are filtered by today's
 * operational date, matching the dashboard cards: Today's Bookings, Pending,
 * Confirmed, Checked In, Completed Today, Cancelled Today, and revenue.
 */
@ExtendWith(MockitoExtension.class)
class DashboardStatsTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private AvailabilityClient availabilityClient;
    @Mock private AvailabilityClientFallback availabilityFallback;
    @Mock private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private SystemSettingsService systemSettingsService;
    @Mock private PricingService pricingService;
    @Mock private BookingEventLogService eventLogService;
    @Mock private SagaOrchestrator sagaOrchestrator;

    @InjectMocks private BookingService bookingService;

    private final LocalDate today = LocalDate.of(2026, 4, 3);

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    // ── Non-binge path (no X-Binge-Id) ──────────────────

    @Nested
    @DisplayName("getDashboardStats — no binge context")
    class NoBingeTests {

        @BeforeEach
        void setUp() {
            BingeContext.clear();
            when(systemSettingsService.getOperationalDate(isNull(), eq(today))).thenReturn(today);
        }

        @Test
        @DisplayName("All counts use today's date (no all-time leaks)")
        void allCountsAreFilteredByToday() {
            when(bookingRepository.countByBookingDate(today)).thenReturn(10L);
            when(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.PENDING)).thenReturn(3L);
            when(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.CONFIRMED)).thenReturn(4L);
            when(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.CANCELLED)).thenReturn(1L);
            when(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.COMPLETED)).thenReturn(2L);
            when(bookingRepository.countByBookingDateAndCheckedIn(today, true)).thenReturn(1L);
            when(bookingRepository.actualRevenueByDate(today)).thenReturn(BigDecimal.valueOf(5000));
            when(bookingRepository.estimatedRevenueByDate(today)).thenReturn(BigDecimal.valueOf(12000));

            DashboardStatsDto stats = bookingService.getDashboardStats(today);

            // Top-row stats (should all be today-only)
            assertThat(stats.getTotalBookings()).isEqualTo(10L);
            assertThat(stats.getPendingBookings()).isEqualTo(3L);
            assertThat(stats.getConfirmedBookings()).isEqualTo(4L);
            assertThat(stats.getCancelledBookings()).isEqualTo(1L);
            assertThat(stats.getCompletedBookings()).isEqualTo(2L);

            // Today-specific stats
            assertThat(stats.getTodayTotal()).isEqualTo(10L);
            assertThat(stats.getTodayPending()).isEqualTo(3L);
            assertThat(stats.getTodayConfirmed()).isEqualTo(4L);
            assertThat(stats.getTodayCheckedIn()).isEqualTo(1L);
            assertThat(stats.getTodayCompleted()).isEqualTo(2L);
            assertThat(stats.getTodayCancelled()).isEqualTo(1L);

            // Revenue
            assertThat(stats.getTodayRevenue()).isEqualByComparingTo(BigDecimal.valueOf(5000));
            assertThat(stats.getTodayEstimatedRevenue()).isEqualByComparingTo(BigDecimal.valueOf(12000));

            // Verify NO all-time (unfiltered) count methods were called
            verify(bookingRepository, never()).countByStatus(any());
        }

        @Test
        @DisplayName("Yesterday completed booking excluded from todayCompleted")
        void yesterdayCompletedExcluded() {
            // Repo returns 0 for today's completed
            when(bookingRepository.countByBookingDate(today)).thenReturn(5L);
            when(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.COMPLETED)).thenReturn(0L);
            when(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.PENDING)).thenReturn(2L);
            when(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.CONFIRMED)).thenReturn(3L);
            when(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.CANCELLED)).thenReturn(0L);
            when(bookingRepository.countByBookingDateAndCheckedIn(today, true)).thenReturn(0L);
            when(bookingRepository.actualRevenueByDate(today)).thenReturn(BigDecimal.ZERO);
            when(bookingRepository.estimatedRevenueByDate(today)).thenReturn(BigDecimal.valueOf(8000));

            DashboardStatsDto stats = bookingService.getDashboardStats(today);

            assertThat(stats.getTodayCompleted()).isZero();
            assertThat(stats.getCompletedBookings()).isZero();
        }
    }

    // ── Binge-scoped path (with X-Binge-Id) ─────────────

    @Nested
    @DisplayName("getDashboardStats — binge context")
    class BingeScopedTests {

        private final Long bingeId = 42L;

        @BeforeEach
        void setUp() {
            BingeContext.setBingeId(bingeId);
            when(systemSettingsService.getOperationalDate(eq(bingeId), eq(today))).thenReturn(today);
        }

        @Test
        @DisplayName("All binge-scoped counts use today's date")
        void allBingeCountsFilteredByToday() {
            when(bookingRepository.countByBingeIdAndBookingDate(bingeId, today)).thenReturn(8L);
            when(bookingRepository.countByBingeIdAndBookingDateAndStatus(bingeId, today, BookingStatus.PENDING)).thenReturn(2L);
            when(bookingRepository.countByBingeIdAndBookingDateAndStatus(bingeId, today, BookingStatus.CONFIRMED)).thenReturn(3L);
            when(bookingRepository.countByBingeIdAndBookingDateAndStatus(bingeId, today, BookingStatus.CANCELLED)).thenReturn(1L);
            when(bookingRepository.countByBingeIdAndBookingDateAndStatus(bingeId, today, BookingStatus.COMPLETED)).thenReturn(2L);
            when(bookingRepository.countByBingeAndDateAndCheckedIn(bingeId, today, true)).thenReturn(1L);
            when(bookingRepository.actualRevenueByBingeAndDate(bingeId, today)).thenReturn(BigDecimal.valueOf(7000));
            when(bookingRepository.estimatedRevenueByBingeAndDate(bingeId, today)).thenReturn(BigDecimal.valueOf(15000));

            DashboardStatsDto stats = bookingService.getDashboardStats(today);

            assertThat(stats.getTotalBookings()).isEqualTo(8L);
            assertThat(stats.getPendingBookings()).isEqualTo(2L);
            assertThat(stats.getConfirmedBookings()).isEqualTo(3L);
            assertThat(stats.getCancelledBookings()).isEqualTo(1L);
            assertThat(stats.getCompletedBookings()).isEqualTo(2L);
            assertThat(stats.getTodayCheckedIn()).isEqualTo(1L);
            assertThat(stats.getTodayRevenue()).isEqualByComparingTo(BigDecimal.valueOf(7000));
            assertThat(stats.getTodayEstimatedRevenue()).isEqualByComparingTo(BigDecimal.valueOf(15000));

            // Verify NO all-time (unfiltered) count methods were called
            verify(bookingRepository, never()).countByBingeIdAndStatus(anyLong(), any());
        }

        @Test
        @DisplayName("Checked-in count is zero after all bookings checked out")
        void checkedInZeroAfterAllCheckedOut() {
            when(bookingRepository.countByBingeIdAndBookingDate(bingeId, today)).thenReturn(3L);
            when(bookingRepository.countByBingeIdAndBookingDateAndStatus(bingeId, today, BookingStatus.PENDING)).thenReturn(0L);
            when(bookingRepository.countByBingeIdAndBookingDateAndStatus(bingeId, today, BookingStatus.CONFIRMED)).thenReturn(0L);
            when(bookingRepository.countByBingeIdAndBookingDateAndStatus(bingeId, today, BookingStatus.CANCELLED)).thenReturn(0L);
            when(bookingRepository.countByBingeIdAndBookingDateAndStatus(bingeId, today, BookingStatus.COMPLETED)).thenReturn(3L);
            when(bookingRepository.countByBingeAndDateAndCheckedIn(bingeId, today, true)).thenReturn(0L);
            when(bookingRepository.actualRevenueByBingeAndDate(bingeId, today)).thenReturn(BigDecimal.valueOf(9000));
            when(bookingRepository.estimatedRevenueByBingeAndDate(bingeId, today)).thenReturn(BigDecimal.valueOf(9000));

            DashboardStatsDto stats = bookingService.getDashboardStats(today);

            assertThat(stats.getTodayCheckedIn()).isZero();
            assertThat(stats.getTodayCompleted()).isEqualTo(3L);
        }
    }

    // ── Audit: checkedIn reset on auto-complete ──────────

    @Nested
    @DisplayName("runAudit — checkedIn reset")
    class AuditCheckedInResetTests {

        private final Long bingeId = 42L;
        private final LocalDate opDate = LocalDate.of(2026, 4, 2);

        @BeforeEach
        void setUp() {
            BingeContext.setBingeId(bingeId);
        }

        @Test
        @DisplayName("Audit resets checkedIn=false when auto-completing CHECKED_IN bookings")
        void auditResetsCheckedIn() {
            // Operational date is 04-02, client date is 04-03 (next day, audit allowed)
            LocalDate clientDate = LocalDate.of(2026, 4, 3);
            LocalDateTime clientNow = LocalDateTime.of(clientDate, LocalTime.of(0, 30));

            when(systemSettingsService.getOperationalDate(eq(bingeId), eq(clientDate))).thenReturn(opDate);
            when(systemSettingsService.advanceOperationalDate(eq(bingeId), eq(clientDate))).thenReturn(clientDate);

            Booking checkedInBooking = Booking.builder()
                    .id(1L).bookingRef("SKBG26100001")
                    .bingeId(bingeId).customerId(1L).customerName("Test User")
                    .eventType(EventType.builder().id(1L).name("Birthday").basePrice(BigDecimal.ZERO)
                            .hourlyRate(BigDecimal.ZERO).build())
                    .bookingDate(opDate).startTime(LocalTime.of(14, 0))
                    .durationHours(2).durationMinutes(120)
                    .totalAmount(BigDecimal.valueOf(5000))
                    .status(BookingStatus.CHECKED_IN)
                    .paymentStatus(PaymentStatus.SUCCESS)
                    .checkedIn(true)
                    .addOns(new ArrayList<>())
                    .build();

            when(bookingRepository.findActiveBookingsByBingeAndDate(bingeId, opDate))
                    .thenReturn(List.of(checkedInBooking));
            when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

            AuditResultDto result = bookingService.runAudit(clientDate, clientNow);

            assertThat(result.getMarkedCompleted()).isEqualTo(1);
            assertThat(checkedInBooking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            assertThat(checkedInBooking.isCheckedIn()).isFalse();
        }
    }
}
