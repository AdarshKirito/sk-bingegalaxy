package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.client.AvailabilityClientFallback;
import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private AvailabilityClient availabilityClient;
    @Mock private AvailabilityClientFallback availabilityFallback;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private SystemSettingsService systemSettingsService;
    @Mock private PricingService pricingService;
    @Mock private BookingEventLogService eventLogService;
    @Mock private SagaOrchestrator sagaOrchestrator;

    @InjectMocks private BookingService bookingService;

    private EventType eventType;
    private Booking testBooking;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingService, "refPrefix", "SKBG");
                ReflectionTestUtils.setField(bookingService, "maxPendingPerCustomer", 2);
                ReflectionTestUtils.setField(bookingService, "cooldownMinutesAfterTimeout", 10);

        eventType = EventType.builder()
                .id(1L).name("Birthday Party")
                .basePrice(BigDecimal.valueOf(2000))
                .hourlyRate(BigDecimal.valueOf(500))
                .minHours(2).maxHours(8)
                .active(true).build();

        testBooking = Booking.builder()
                .id(1L).bookingRef("SKBG25123456")
                .customerId(1L).customerName("John Doe")
                .customerEmail("john@example.com").customerPhone("9876543210")
                .eventType(eventType)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .baseAmount(BigDecimal.valueOf(3500))
                .addOnAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.valueOf(3500))
                .status(BookingStatus.PENDING)
                .paymentStatus(PaymentStatus.PENDING)
                .checkedIn(false)
                .addOns(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ── Create booking tests ─────────────────────────────

    @Test
    void createBooking_success() {
        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(1L)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .build();

        when(eventTypeRepository.findById(1L)).thenReturn(Optional.of(eventType));
        when(availabilityClient.checkSlotAvailable(request.getBookingDate(), 840, 180)).thenReturn(Boolean.TRUE);
        when(pricingService.resolveEventPrice(anyLong(), eq(1L)))
                .thenReturn(new PricingService.ResolvedEventPrice(
                        BigDecimal.valueOf(2000), BigDecimal.valueOf(500), BigDecimal.ZERO, "DEFAULT", null));
        when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

        BookingDto result = bookingService.createBooking(
                request, 1L, "John Doe", "john@example.com", "9876543210");

        assertThat(result.getBookingRef()).isEqualTo("SKBG25123456");
        assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void createBooking_invalidDuration_throwsException() {
        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(1L)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationMinutes(15) // below 30-min minimum
                .build();

        when(eventTypeRepository.findById(1L)).thenReturn(Optional.of(eventType));

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duration must be between");
    }

    @Test
    void createBooking_slotNotAvailable_throwsException() {
        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(1L)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .build();

        when(eventTypeRepository.findById(1L)).thenReturn(Optional.of(eventType));

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void createBooking_eventTypeNotFound_throwsException() {
        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(99L).build();

        when(eventTypeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Get booking by ref ───────────────────────────────

    @Test
    void getByRef_success() {
        when(bookingRepository.findByBookingRef("SKBG25123456"))
                .thenReturn(Optional.of(testBooking));

        BookingDto result = bookingService.getByRef("SKBG25123456");
        assertThat(result.getBookingRef()).isEqualTo("SKBG25123456");
    }

    @Test
    void getByRef_notFound_throwsException() {
        when(bookingRepository.findByBookingRef("INVALID")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getByRef("INVALID"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Cancel booking ───────────────────────────────────

    @Test
    void cancelBooking_success() {
        when(bookingRepository.findByBookingRef("SKBG25123456"))
                .thenReturn(Optional.of(testBooking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

        BookingDto result = bookingService.cancelBooking("SKBG25123456");

        assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void cancelBooking_alreadyCancelled_throwsException() {
        testBooking.setStatus(BookingStatus.CANCELLED);
        when(bookingRepository.findByBookingRef("SKBG25123456"))
                .thenReturn(Optional.of(testBooking));

        assertThatThrownBy(() -> bookingService.cancelBooking("SKBG25123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already cancelled");
    }

    // ── Update payment status ────────────────────────────

    @Test
    void updatePaymentStatus_success_confirmsBooking() {
        when(bookingRepository.findByBookingRef("SKBG25123456"))
                .thenReturn(Optional.of(testBooking));

        bookingService.updatePaymentStatus("SKBG25123456", PaymentStatus.SUCCESS, "UPI");

        assertThat(testBooking.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository).save(testBooking);
    }

    // ── Customer bookings ────────────────────────────────

    @Test
    void getCustomerBookings_returnsList() {
        when(bookingRepository.findByCustomerIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(testBooking));

        List<BookingDto> result = bookingService.getCustomerBookings(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBookingRef()).isEqualTo("SKBG25123456");
    }

    // ── Event types ──────────────────────────────────────

    @Test
    void getActiveEventTypes_returnsList() {
        when(eventTypeRepository.findByActiveTrue()).thenReturn(List.of(eventType));

        List<EventTypeDto> result = bookingService.getActiveEventTypes();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Birthday Party");
    }
}
