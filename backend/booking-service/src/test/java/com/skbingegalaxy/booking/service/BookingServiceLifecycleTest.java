package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.client.AvailabilityClientFallback;
import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for booking lifecycle: anti-abuse, cancel, early checkout,
 * payment status, state transitions, and worst-case edge cases.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceLifecycleTest {

    @Mock private BookingRepository bookingRepository;
        @Mock private BingeRepository bingeRepository;
    @Mock private BookingAddOnRepository bookingAddOnRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private RateCodeEventPricingRepository rateCodeEventPricingRepository;
    @Mock private RateCodeAddonPricingRepository rateCodeAddonPricingRepository;
    @Mock private CustomerEventPricingRepository customerEventPricingRepository;
    @Mock private CustomerAddonPricingRepository customerAddonPricingRepository;
        @Mock private CancellationTierRepository cancellationTierRepository;
    @Mock private AvailabilityClient availabilityClient;
    @Spy  private AvailabilityClientFallback availabilityFallback;
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
        BingeContext.clear();
        ReflectionTestUtils.setField(bookingService, "availabilityClient", availabilityClient);
        ReflectionTestUtils.setField(bookingService, "internalApiSecret", "test-secret");
        ReflectionTestUtils.setField(bookingService, "refPrefix", "SKBG");
        ReflectionTestUtils.setField(bookingService, "maxPendingPerCustomer", 2);
        ReflectionTestUtils.setField(bookingService, "cooldownMinutesAfterTimeout", 10);
        lenient().when(bingeRepository.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(cancellationTierRepository.findByBingeIdOrderByHoursBeforeStartDesc(anyLong())).thenReturn(List.of());

        eventType = EventType.builder()
                .id(1L).bingeId(11L).name("Birthday Party")
                .basePrice(BigDecimal.valueOf(2000))
                .hourlyRate(BigDecimal.valueOf(500))
                .pricePerGuest(BigDecimal.ZERO)
                .minHours(2).maxHours(8)
                .active(true).build();

        testBooking = Booking.builder()
                .id(1L).bookingRef("SKBG25123456").bingeId(11L)
                .customerId(1L).customerName("John Doe")
                .customerEmail("john@example.com").customerPhone("9876543210")
                .eventType(eventType)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3).durationMinutes(180)
                .baseAmount(BigDecimal.valueOf(3500))
                .addOnAmount(BigDecimal.ZERO)
                .guestAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.valueOf(3500))
                .status(BookingStatus.PENDING)
                .paymentStatus(PaymentStatus.PENDING)
                .checkedIn(false)
                .addOns(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    // ── Anti-abuse: Pending Limit ────────────────────────────

    @Nested
    @DisplayName("Anti-abuse: Pending Booking Limit")
    class PendingLimitTests {

        @Test
        @DisplayName("rejects booking when customer has max pending bookings")
        void createBooking_maxPending_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(2L);

            CreateBookingRequest request = CreateBookingRequest.builder()
                    .eventTypeId(1L)
                    .bookingDate(LocalDate.now().plusDays(7))
                    .startTime(LocalTime.of(14, 0))
                    .durationHours(3)
                    .build();

            assertThatThrownBy(() -> bookingService.createBooking(
                    request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pending booking(s)");
        }

        @Test
        @DisplayName("allows booking when customer has less than max pending")
        void createBooking_belowMaxPending_proceeds() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(1L);
            when(bookingRepository.countRecentTimeoutCancellations(eq(1L), any(LocalDateTime.class))).thenReturn(0L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));
            when(availabilityClient.checkSlotAvailable(anyString(), any(), anyLong(), anyInt(), anyInt()))
                    .thenReturn(Boolean.TRUE);
            when(bookingRepository.findActiveBookingsByBingeAndDate(eq(11L), any(LocalDate.class))).thenReturn(List.of());
            when(pricingService.resolveEventPrice(anyLong(), eq(1L)))
                    .thenReturn(new PricingService.ResolvedEventPrice(
                        BigDecimal.valueOf(2000), BigDecimal.valueOf(500), BigDecimal.ZERO, "DEFAULT", null));
            when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

            BookingDto result = bookingService.createBooking(
                    CreateBookingRequest.builder()
                        .eventTypeId(1L)
                        .bookingDate(LocalDate.now().plusDays(7))
                        .startTime(LocalTime.of(14, 0))
                        .durationHours(3).build(),
                    1L, "John", "john@example.com", "9876543210");

            assertThat(result).isNotNull();
        }
    }

    // ── Anti-abuse: Cooldown After Timeout ───────────────────

    @Nested
    @DisplayName("Anti-abuse: Cooldown After Timeout")
    class CooldownTests {

        @Test
        @DisplayName("rejects booking when too many recent timeouts")
        void createBooking_tooManyTimeouts_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(0L);
            when(bookingRepository.countRecentTimeoutCancellations(eq(1L), any(LocalDateTime.class))).thenReturn(2L);

            CreateBookingRequest request = CreateBookingRequest.builder()
                    .eventTypeId(1L)
                    .bookingDate(LocalDate.now().plusDays(7))
                    .startTime(LocalTime.of(14, 0))
                    .durationHours(3)
                    .build();

            assertThatThrownBy(() -> bookingService.createBooking(
                    request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("auto-cancelled recently");
        }

        @Test
        @DisplayName("allows booking when only 1 recent timeout")
        void createBooking_oneTimeout_proceeds() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(0L);
            when(bookingRepository.countRecentTimeoutCancellations(eq(1L), any(LocalDateTime.class))).thenReturn(1L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));
            when(availabilityClient.checkSlotAvailable(anyString(), any(), anyLong(), anyInt(), anyInt()))
                    .thenReturn(Boolean.TRUE);
            when(bookingRepository.findActiveBookingsByBingeAndDate(eq(11L), any(LocalDate.class))).thenReturn(List.of());
            when(pricingService.resolveEventPrice(anyLong(), eq(1L)))
                    .thenReturn(new PricingService.ResolvedEventPrice(
                        BigDecimal.valueOf(2000), BigDecimal.valueOf(500), BigDecimal.ZERO, "DEFAULT", null));
            when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

            BookingDto result = bookingService.createBooking(
                    CreateBookingRequest.builder()
                        .eventTypeId(1L)
                        .bookingDate(LocalDate.now().plusDays(7))
                        .startTime(LocalTime.of(14, 0))
                        .durationHours(3).build(),
                    1L, "John", "john@example.com", "9876543210");

            assertThat(result).isNotNull();
        }
    }

    // ── Duration Validation ──────────────────────────────────

    @Nested
    @DisplayName("Duration Validation")
    class DurationTests {

        @Test
        @DisplayName("rejects duration that is not 30-minute multiple")
        void createBooking_non30MinIncrement_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(0L);
            when(bookingRepository.countRecentTimeoutCancellations(eq(1L), any())).thenReturn(0L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));

            CreateBookingRequest request = CreateBookingRequest.builder()
                    .eventTypeId(1L)
                    .bookingDate(LocalDate.now().plusDays(7))
                    .startTime(LocalTime.of(14, 0))
                    .durationMinutes(45) // not a 30-min multiple
                    .build();

            assertThatThrownBy(() -> bookingService.createBooking(
                    request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("30-minute increments");
        }

        @Test
        @DisplayName("rejects duration below 30 minutes")
        void createBooking_tooShort_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(0L);
            when(bookingRepository.countRecentTimeoutCancellations(eq(1L), any())).thenReturn(0L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));

            CreateBookingRequest request = CreateBookingRequest.builder()
                    .eventTypeId(1L)
                    .bookingDate(LocalDate.now().plusDays(7))
                    .startTime(LocalTime.of(14, 0))
                    .durationMinutes(15)
                    .build();

            assertThatThrownBy(() -> bookingService.createBooking(
                    request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 30 minutes and 12 hours");
        }

        @Test
        @DisplayName("rejects duration above 720 minutes")
        void createBooking_tooLong_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(0L);
            when(bookingRepository.countRecentTimeoutCancellations(eq(1L), any())).thenReturn(0L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));

            CreateBookingRequest request = CreateBookingRequest.builder()
                    .eventTypeId(1L)
                    .bookingDate(LocalDate.now().plusDays(7))
                    .startTime(LocalTime.of(14, 0))
                    .durationMinutes(750) // > 720
                    .build();

            assertThatThrownBy(() -> bookingService.createBooking(
                    request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 30 minutes and 12 hours");
        }
    }

    // ── Cancel Booking ───────────────────────────────────────

    @Nested
    @DisplayName("Cancel Booking")
    class CancelTests {

        @Test
        @DisplayName("customer can cancel own PENDING booking")
        void cancelByCustomer_pending_ok() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));
            when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

            bookingService.cancelBookingByCustomer("SKBG25123456", 1L);

            assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        @DisplayName("customer cannot cancel CONFIRMED booking")
        void cancelByCustomer_confirmed_throws() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CONFIRMED);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.cancelBookingByCustomer("SKBG25123456", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only PENDING bookings");
        }

        @Test
        @DisplayName("customer cannot cancel another customer's booking")
        void cancelByCustomer_differentCustomer_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.cancelBookingByCustomer("SKBG25123456", 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not authorised");
        }

        @Test
        @DisplayName("admin cannot cancel already cancelled booking")
        void cancelBooking_alreadyCancelled_throws() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CANCELLED);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.cancelBooking("SKBG25123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already cancelled");
        }

        @Test
        @DisplayName("cannot cancel COMPLETED booking")
        void cancelBooking_completed_throws() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.COMPLETED);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.cancelBooking("SKBG25123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot cancel a COMPLETED booking");
        }

        @Test
        @DisplayName("cannot cancel NO_SHOW booking")
        void cancelBooking_noShow_throws() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.NO_SHOW);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.cancelBooking("SKBG25123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot cancel a NO_SHOW booking");
        }

        @Test
        @DisplayName("system cancel works without binge context")
        void cancelForSystem_noBingeRequired() {
            when(bookingRepository.findByBookingRef("SKBG25123456"))
                    .thenReturn(Optional.of(testBooking));
            when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

            BookingDto result = bookingService.cancelBookingForSystem("SKBG25123456", "Payment timeout");

            assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            verify(bookingRepository).findByBookingRef("SKBG25123456");
            verify(bookingRepository, never()).findByBookingRefAndBingeId(anyString(), anyLong());
        }
    }

    // ── Early Checkout ───────────────────────────────────────

    @Nested
    @DisplayName("Early Checkout")
    class EarlyCheckoutTests {

        @Test
        @DisplayName("rejects early checkout for non-CHECKED_IN booking")
        void earlyCheckout_notCheckedIn_throws() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CONFIRMED);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.earlyCheckout("SKBG25123456", LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHECKED_IN");
        }

        @Test
        @DisplayName("rejects checkout with PENDING payment")
        void earlyCheckout_pendingPayment_throws() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CHECKED_IN);
            testBooking.setPaymentStatus(PaymentStatus.PENDING);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.earlyCheckout("SKBG25123456", LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Collect payment before checking out");
        }

        @Test
        @DisplayName("rejects checkout with FAILED payment")
        void earlyCheckout_failedPayment_throws() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CHECKED_IN);
            testBooking.setPaymentStatus(PaymentStatus.FAILED);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.earlyCheckout("SKBG25123456", LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no valid payment");
        }

        @Test
        @DisplayName("performs early checkout with time remaining")
        void earlyCheckout_earlyDeparture_records() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CHECKED_IN);
            testBooking.setPaymentStatus(PaymentStatus.SUCCESS);
            testBooking.setBookingDate(LocalDate.now());
            testBooking.setStartTime(LocalTime.of(14, 0));
            testBooking.setDurationMinutes(180);
            testBooking.setDurationHours(3);

            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));
            when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

            // Checkout 1 hour in (at 15:00 when booking was 14:00-17:00)
            LocalDateTime checkoutTime = LocalDateTime.of(LocalDate.now(), LocalTime.of(15, 0));
            BookingDto result = bookingService.earlyCheckout("SKBG25123456", checkoutTime);

            assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            assertThat(testBooking.isCheckedIn()).isFalse();
            assertThat(testBooking.getActualUsedMinutes()).isEqualTo(60);
            assertThat(testBooking.getEarlyCheckoutNote()).contains("Early checkout");
        }

        @Test
        @DisplayName("normal checkout when past scheduled end time")
        void earlyCheckout_pastEndTime_normalCheckout() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CHECKED_IN);
            testBooking.setPaymentStatus(PaymentStatus.SUCCESS);
            testBooking.setBookingDate(LocalDate.now());
            testBooking.setStartTime(LocalTime.of(14, 0));
            testBooking.setDurationMinutes(180);
            testBooking.setDurationHours(3);

            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));
            when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

            // Checkout after scheduled end (18:00 when booking was 14:00-17:00)
            LocalDateTime checkoutTime = LocalDateTime.of(LocalDate.now(), LocalTime.of(18, 0));
            BookingDto result = bookingService.earlyCheckout("SKBG25123456", checkoutTime);

            assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            assertThat(testBooking.getActualUsedMinutes()).isNull(); // not set for non-early
        }
    }

    // ── Payment Status Updates ───────────────────────────────

    @Nested
    @DisplayName("Payment Status Updates")
    class PaymentStatusTests {

        @Test
        @DisplayName("SUCCESS payment confirms PENDING booking")
        void updatePayment_success_confirmsPending() {
            when(bookingRepository.findByBookingRef("SKBG25123456"))
                    .thenReturn(Optional.of(testBooking));

            bookingService.updatePaymentStatus("SKBG25123456", PaymentStatus.SUCCESS, "UPI");

            assertThat(testBooking.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(testBooking.getPaymentMethod()).isEqualTo("UPI");
        }

        @Test
        @DisplayName("SUCCESS payment does not overwrite CHECKED_IN status")
        void updatePayment_success_doesNotOverwriteCheckedIn() {
            testBooking.setStatus(BookingStatus.CHECKED_IN);
            when(bookingRepository.findByBookingRef("SKBG25123456"))
                    .thenReturn(Optional.of(testBooking));

            bookingService.updatePaymentStatus("SKBG25123456", PaymentStatus.SUCCESS, "CASH");

            assertThat(testBooking.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.CHECKED_IN); // not overwritten
        }

        @Test
        @DisplayName("FAILED payment does not change booking status")
        void updatePayment_failed_keepsPending() {
            when(bookingRepository.findByBookingRef("SKBG25123456"))
                    .thenReturn(Optional.of(testBooking));

            bookingService.updatePaymentStatus("SKBG25123456", PaymentStatus.FAILED, "CARD");

            assertThat(testBooking.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.PENDING);
        }

        @Test
        @DisplayName("payment update not found throws")
        void updatePayment_notFound_throws() {
            when(bookingRepository.findByBookingRef("INVALID")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.updatePaymentStatus("INVALID", PaymentStatus.SUCCESS, "UPI"))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── State Transitions ────────────────────────────────────

    @Nested
    @DisplayName("State Transitions via updateBooking")
    class StateTransitionTests {

        @Test
        @DisplayName("PENDING -> CONFIRMED is valid")
        void pendingToConfirmed_ok() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateBookingRequest request = new UpdateBookingRequest();
            request.setStatus("CONFIRMED");

            bookingService.updateBooking("SKBG25123456", request);

            assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        @DisplayName("PENDING -> COMPLETED is invalid")
        void pendingToCompleted_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            UpdateBookingRequest request = new UpdateBookingRequest();
            request.setStatus("COMPLETED");

            assertThatThrownBy(() -> bookingService.updateBooking("SKBG25123456", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot transition");
        }

        @Test
        @DisplayName("CANCELLED is a terminal state")
        void cancelledToAnything_throws() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CANCELLED);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            UpdateBookingRequest request = new UpdateBookingRequest();
            request.setStatus("CONFIRMED");

            assertThatThrownBy(() -> bookingService.updateBooking("SKBG25123456", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot transition");
        }

        @Test
        @DisplayName("invalid status string throws BusinessException")
        void invalidStatusString_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));

            UpdateBookingRequest request = new UpdateBookingRequest();
            request.setStatus("NOT_A_STATUS");

            assertThatThrownBy(() -> bookingService.updateBooking("SKBG25123456", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid booking status");
        }

        @Test
        @DisplayName("checkedIn=true moves to CHECKED_IN")
        void checkedInTrue_updatesStatus() {
            BingeContext.setBingeId(11L);
            testBooking.setStatus(BookingStatus.CONFIRMED);
            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(testBooking));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateBookingRequest request = new UpdateBookingRequest();
            request.setCheckedIn(true);

            bookingService.updateBooking("SKBG25123456", request);

            assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
            assertThat(testBooking.isCheckedIn()).isTrue();
        }
    }

    // ── Availability Service Fallback ────────────────────────

    @Nested
    @DisplayName("Availability Service Edge Cases")
    class AvailabilityTests {

        @Test
        @DisplayName("null availability response throws")
        void createBooking_availabilityNull_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(0L);
            when(bookingRepository.countRecentTimeoutCancellations(eq(1L), any())).thenReturn(0L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));
            when(availabilityClient.checkSlotAvailable(anyString(), any(), anyLong(), anyInt(), anyInt()))
                    .thenReturn(null);

            CreateBookingRequest request = CreateBookingRequest.builder()
                    .eventTypeId(1L)
                    .bookingDate(LocalDate.now().plusDays(7))
                    .startTime(LocalTime.of(14, 0))
                    .durationHours(3)
                    .build();

            assertThatThrownBy(() -> bookingService.createBooking(
                    request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("temporarily unavailable");
        }

        @Test
        @DisplayName("time conflict with existing booking throws")
        void createBooking_timeConflict_throws() {
            BingeContext.setBingeId(11L);
            when(bookingRepository.countPendingByCustomerId(1L)).thenReturn(0L);
            when(bookingRepository.countRecentTimeoutCancellations(eq(1L), any())).thenReturn(0L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));
            when(availabilityClient.checkSlotAvailable(anyString(), any(), anyLong(), anyInt(), anyInt()))
                    .thenReturn(Boolean.TRUE);
            // Existing booking overlaps
            Booking existing = Booking.builder()
                    .id(99L).status(BookingStatus.CONFIRMED)
                    .startTime(LocalTime.of(13, 0)).durationMinutes(180).durationHours(3)
                    .build();
            when(bookingRepository.findActiveBookingsByBingeAndDate(eq(11L), any(LocalDate.class)))
                    .thenReturn(List.of(existing));

            CreateBookingRequest request = CreateBookingRequest.builder()
                    .eventTypeId(1L)
                    .bookingDate(LocalDate.now().plusDays(7))
                    .startTime(LocalTime.of(14, 0))
                    .durationHours(3)
                    .build();

            assertThatThrownBy(() -> bookingService.createBooking(
                    request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("conflicts with an existing booking");
        }
    }

    // ── Binge Context Enforcement ────────────────────────────

    @Nested
    @DisplayName("Binge Context Enforcement")
    class BingeContextTests {

        @Test
        @DisplayName("getByRef without binge throws")
        void getByRef_noBinge_throws() {
            assertThatThrownBy(() -> bookingService.getByRef("SKBG25123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }

        @Test
        @DisplayName("cancelBooking without binge throws")
        void cancelBooking_noBinge_throws() {
            assertThatThrownBy(() -> bookingService.cancelBooking("SKBG25123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }

        @Test
        @DisplayName("earlyCheckout without binge throws")
        void earlyCheckout_noBinge_throws() {
            assertThatThrownBy(() -> bookingService.earlyCheckout("SKBG25123456", LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }
    }
}
