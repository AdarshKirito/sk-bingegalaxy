package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.client.AvailabilityClientFallback;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
        @Mock private BingeRepository bingeRepository;
        @Mock private BookingReviewRepository bookingReviewRepository;
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
    @Mock private VenueRoomRepository venueRoomRepository;
        @Mock private com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService loyaltyMemberService;
        @Mock private com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyMembershipRepository loyaltyMembershipRepository;
        @Mock private com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService loyaltyConfigService;
        @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
        @Mock private com.skbingegalaxy.booking.service.CustomerFreezeService customerFreezeService;
        @Mock private com.skbingegalaxy.booking.repository.SlotHoldRepository slotHoldRepository;
        // Pre-existing service field that was never mocked in this fixture —
        // adding here so cancel / create flows can publish without NPE.
        @Mock private com.skbingegalaxy.booking.service.BookingEventPublisher bookingEventPublisher;
        @Mock private com.skbingegalaxy.booking.service.BookingAnalyticsMetrics analyticsMetrics;
        @Mock private com.skbingegalaxy.booking.service.BookingRiskEvaluator bookingRiskEvaluator;
        @Mock private com.skbingegalaxy.booking.repository.BookingTransferRepository bookingTransferRepository;
        // Mocked so @InjectMocks satisfies the constructor; replaced in setUp()
        // with a real instance wired against the same mocked dependencies so
        // existing assertions on bookingRepository.save() / eventLogService
        // continue to observe the SM's internal calls.
        @Mock private com.skbingegalaxy.booking.service.statemachine.BookingStateMachine stateMachineMock;
        @Mock private VenueClockService venueClock;
        @Mock private TaxService taxService;
@Mock private TurnoverPolicy turnoverPolicy;                 // V81 — occupancy buffers
@Mock private BookingWindowPolicy bookingWindowPolicy;   // V84 — window + duration rules (void methods no-op by default)

    @InjectMocks private BookingService bookingService;

    private EventType eventType;
    private Booking testBooking;

    @BeforeEach
    void setUp() {
        // V81: no turnover buffers by default, so every pre-existing assertion
        // about occupancy keeps its original billable-interval semantics. Tests
        // that exercise buffers override this stub locally.
        lenient().when(turnoverPolicy.resolve(any(), any())).thenReturn(TurnoverPolicy.Buffers.NONE);
        BingeContext.clear();
        // Force correct mock — @InjectMocks can't disambiguate AvailabilityClient
        // from AvailabilityClientFallback (which implements AvailabilityClient)
        // because Lombok-generated constructors don't preserve parameter names.
        ReflectionTestUtils.setField(bookingService, "availabilityClient", availabilityClient);
        ReflectionTestUtils.setField(bookingService, "eventPublisher", eventPublisher);
        // Replace the mocked state-machine with a real one wired to the same
        // mocked repository + event log so existing tests can keep verifying
        // bookingRepository.save() and eventLogService.logEventFull() calls
        // — the SM forwards to those collaborators.
        ReflectionTestUtils.setField(bookingService, "stateMachine",
            new com.skbingegalaxy.booking.service.statemachine.BookingStateMachine(
                bookingRepository, eventLogService));
        ReflectionTestUtils.setField(bookingService, "internalApiSecret", "test-internal-secret");
        ReflectionTestUtils.setField(bookingService, "refPrefix", "SKBG");
                ReflectionTestUtils.setField(bookingService, "maxPendingPerCustomer", 2);
                ReflectionTestUtils.setField(bookingService, "cooldownMinutesAfterTimeout", 10);
                ReflectionTestUtils.setField(bookingService, "maxBookingHorizonDays", 365);
                ReflectionTestUtils.setField(bookingService, "defaultOpeningHour", 10);
                ReflectionTestUtils.setField(bookingService, "defaultClosingHour", 23);
                lenient().when(venueClock.zoneOf(any())).thenReturn(ZoneOffset.UTC);
        lenient().when(venueClock.today(any())).thenReturn(LocalDate.now(ZoneOffset.UTC));
        lenient().when(venueClock.defaultZone()).thenReturn(ZoneOffset.UTC);
        lenient().when(taxService.compute(any(Long.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TaxComputationResult.builder()
                        .subtotal(BigDecimal.ZERO).totalTax(BigDecimal.ZERO)
                        .totalInclusiveTax(BigDecimal.ZERO).lines(List.of()).build());
        lenient().when(taxService.compute(any(com.skbingegalaxy.booking.tax.provider.TaxContext.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TaxComputationResult.builder()
                        .subtotal(BigDecimal.ZERO).totalTax(BigDecimal.ZERO)
                        .totalInclusiveTax(BigDecimal.ZERO).lines(List.of()).build());
        lenient().when(taxService.venueContext(any())).thenAnswer(inv ->
                com.skbingegalaxy.booking.tax.provider.TaxContext.builder()
                        .bingeId(inv.getArgument(0)).customerType("B2C").productType("BOOKING"));
        lenient().when(loyaltyMemberService.redeemForBooking(anyLong(), anyLong(), anyString(), anyLong(), any(BigDecimal.class)))
                        .thenReturn(new com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService.RedemptionResult(0L, BigDecimal.ZERO));
        lenient().when(bingeRepository.findById(anyLong())).thenAnswer(inv ->
            Optional.of(Binge.builder().id((Long) inv.getArgument(0)).build()));
        lenient().when(cancellationTierRepository.findByBingeIdOrderByHoursBeforeStartDesc(anyLong())).thenReturn(List.of());

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
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    // ── Create booking tests ─────────────────────────────

    @Test
    void createBooking_success() {
                BingeContext.setBingeId(11L);

        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(1L)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .build();

        when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));
        when(bookingRepository.findActiveBookingsByBingeAndDate(eq(11L), any(java.time.LocalDate.class))).thenReturn(List.of());
        when(availabilityClient.checkSlotAvailable(anyString(), any(java.time.LocalDate.class), anyLong(), anyInt(), anyInt()))
                .thenReturn(Boolean.TRUE);
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
                BingeContext.setBingeId(11L);

        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(1L)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationMinutes(15) // below 30-min minimum
                .build();

        when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duration must be between");
    }

    @Test
    void createBooking_slotNotAvailable_throwsException() {
                BingeContext.setBingeId(11L);

        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(1L)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .build();

        when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));
        when(availabilityClient.checkSlotAvailable(anyString(), any(LocalDate.class), anyLong(), anyInt(), anyInt()))
                .thenReturn(Boolean.FALSE);

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void createBooking_eventTypeNotFound_throwsException() {
        BingeContext.setBingeId(11L);

        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(99L).build();

        when(eventTypeRepository.findByIdAndBingeId(99L, 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createBooking_pastDate_throwsException() {
        BingeContext.setBingeId(11L);
        // venueClock.zoneOf(...) is stubbed to ZoneOffset.UTC in setUp, so "venue-local"
        // here is real UTC now.
        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(1L)
                .bookingDate(LocalDate.now(ZoneOffset.UTC).minusDays(1))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .build();

        when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Booking date cannot be in the past");
    }

    @Test
    void createBooking_sameDayPastTime_throwsException() {
        // Regression test: booking a slot on today's date whose start time has already
        // elapsed (venue-local) must be rejected here even if the availability-service
        // check is bypassed or serves a stale cached "available" result — see
        // AvailabilityService's equivalent fix for the primary guard.
        BingeContext.setBingeId(11L);
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        // Guard the narrow midnight window where "1 hour ago" would wrap to a later
        // clock reading on the same LocalTime instead of an earlier one.
        Assumptions.assumeTrue(nowUtc.getHour() >= 1, "Skipping near UTC midnight to avoid time-wrap flakiness");

        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(1L)
                .bookingDate(nowUtc.toLocalDate())
                .startTime(nowUtc.toLocalTime().minusHours(1))
                .durationHours(3)
                .build();

        when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already passed for today");
    }

    // ── Get booking by ref ───────────────────────────────

    @Test
    void getByRef_success() {
                BingeContext.setBingeId(11L);
                when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                .thenReturn(Optional.of(testBooking));

        BookingDto result = bookingService.getByRef("SKBG25123456");
        assertThat(result.getBookingRef()).isEqualTo("SKBG25123456");
    }

    @Test
    void getByRef_notFound_throwsException() {
                BingeContext.setBingeId(11L);
                when(bookingRepository.findByBookingRefAndBingeId("INVALID", 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getByRef("INVALID"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

        @Test
        void getByRef_requiresSelectedBinge() {
                assertThatThrownBy(() -> bookingService.getByRef("SKBG25123456"))
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("Select a binge before accessing bookings");
        }

    // ── Cancel booking ───────────────────────────────────

    @Test
    void cancelBooking_success() {
        BingeContext.setBingeId(11L);
        when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                .thenReturn(Optional.of(testBooking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

        BookingDto result = bookingService.cancelBooking("SKBG25123456");

        assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void cancelBooking_alreadyCancelled_throwsException() {
        BingeContext.setBingeId(11L);
        testBooking.setStatus(BookingStatus.CANCELLED);
        when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                .thenReturn(Optional.of(testBooking));

        assertThatThrownBy(() -> bookingService.cancelBooking("SKBG25123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already cancelled");
    }

    @Test
    void cancelBookingForSystem_bypassesSelectedBingeContext() {
        when(bookingRepository.findByBookingRef("SKBG25123456"))
                .thenReturn(Optional.of(testBooking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

        BookingDto result = bookingService.cancelBookingForSystem(
                "SKBG25123456", "Booking auto-cancelled after payment failure");

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(bookingRepository).findByBookingRef("SKBG25123456");
        verify(bookingRepository, never()).findByBookingRefAndBingeId(anyString(), anyLong());
        verify(eventLogService).logEventFull(
                eq(testBooking),
                eq(BookingEventType.CANCELLED),
                eq("PENDING"),
                isNull(),
                eq("SYSTEM"),
                isNull(),
                argThat(d -> d != null && d.contains("PENDING → CANCELLED")
                                       && d.contains("Booking auto-cancelled after payment failure")),
                eq("Booking auto-cancelled after payment failure"),
                isNull(),
                isNull());
    }

    // ── Update payment status ────────────────────────────

    @Test
    void updatePaymentStatus_success_confirmsBooking() {
        when(bookingRepository.findByBookingRef("SKBG25123456"))
                .thenReturn(Optional.of(testBooking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

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

        @Test
        void getBookedSlotsForDate_usesNonLockingReadQuery() {
                LocalDate bookingDate = testBooking.getBookingDate();
                BingeContext.setBingeId(11L);
                when(bookingRepository.findActiveBookingsForReadByBingeAndDate(11L, bookingDate))
                                .thenReturn(List.of(testBooking));

                List<BookedSlotDto> result = bookingService.getBookedSlotsForDate(bookingDate);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getBookingRef()).isEqualTo(testBooking.getBookingRef());
                assertThat(result.get(0).getStartMinute()).isEqualTo(14 * 60);
                assertThat(result.get(0).getDurationMinutes()).isEqualTo(180);
                verify(bookingRepository).findActiveBookingsForReadByBingeAndDate(11L, bookingDate);
                verify(bookingRepository, never()).findActiveBookingsByBingeAndDate(anyLong(), any(LocalDate.class));
        }

    // ── Event types ──────────────────────────────────────

    @Test
    void getActiveEventTypes_returnsList() {
                BingeContext.setBingeId(11L);
                when(eventTypeRepository.findByBingeIdAndActiveTrue(11L)).thenReturn(List.of(eventType));

        List<EventTypeDto> result = bookingService.getActiveEventTypes();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Birthday Party");
    }

    @Test
    void createBooking_rejectsForeignEventTypeWhenBingeSelected() {
        BingeContext.setBingeId(11L);

        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(99L)
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .build();

        when(eventTypeRepository.findByIdAndBingeId(99L, 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(
                request, 1L, "John", "john@example.com", "9876543210"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateEventType_rejectsTemplateOutsideSelectedBinge() {
        BingeContext.setBingeId(11L);

                EventTypeSaveRequest request = new EventTypeSaveRequest();
                request.setName("Updated");
                request.setDescription("Updated");
                request.setBasePrice(BigDecimal.valueOf(1000));
                request.setHourlyRate(BigDecimal.valueOf(250));
                request.setPricePerGuest(BigDecimal.ZERO);
                request.setMinHours(1);
                request.setMaxHours(4);

        when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.updateEventType(1L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

        @Test
        void deleteEventType_requiresInactiveStatus() {
                BingeContext.setBingeId(11L);
                when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));

                assertThatThrownBy(() -> bookingService.deleteEventType(1L))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Deactivate the event type");
        }

        @Test
        void deleteAddOn_rejectsBookingUsage() {
                AddOn addOn = AddOn.builder().id(9L).name("Cake").active(false).price(BigDecimal.TEN).categoryId(1L).build();
                BingeContext.setBingeId(11L);
                when(addOnRepository.findByIdAndBingeId(9L, 11L)).thenReturn(Optional.of(addOn));
                when(bookingAddOnRepository.existsByAddOnId(9L)).thenReturn(true);

                assertThatThrownBy(() -> bookingService.deleteAddOn(9L))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("already used in bookings");
        }

        // ── V85 (G2/G3): external channel reservation ingestion ────────────

        /** Stubs the happy path shared by the ingestion tests below. */
        private void stubChannelHappyPath(String source, String ref) {
                when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(eventType));
                when(bookingRepository.findByBingeIdAndExternalSourceAndExternalRef(11L, source, ref))
                        .thenReturn(Optional.empty());
                when(bookingRepository.findActiveBookingsByBingeAndDate(eq(11L), any(java.time.LocalDate.class)))
                        .thenReturn(List.of());
                when(availabilityClient.checkSlotAvailable(anyString(), any(java.time.LocalDate.class), anyLong(), anyInt(), anyInt()))
                        .thenReturn(Boolean.TRUE);
                when(pricingService.resolveEventPrice(anyLong(), eq(1L)))
                        .thenReturn(new PricingService.ResolvedEventPrice(
                                BigDecimal.valueOf(2000), BigDecimal.valueOf(500), BigDecimal.ZERO, "DEFAULT", null));
                when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);
        }

        private ChannelReservationRequest channelRequest(String source, String ref) {
                return ChannelReservationRequest.builder()
                        .externalSource(source).externalRef(ref)
                        .bingeId(11L).eventTypeId(1L)
                        .bookingDate(LocalDate.now().plusDays(7))
                        .startTime(LocalTime.of(14, 0))
                        .durationMinutes(180).numberOfGuests(4)
                        .guestName("Channel Guest").guestEmail("guest@example.com").guestPhone("9876543210")
                        .build();
        }

        @Test
        void ingestChannelReservation_stampsOriginAndExternalReferences() {
                stubChannelHappyPath("acme-channel", "ACME-1");

                bookingService.ingestChannelReservation(channelRequest("acme-channel", "ACME-1"));

                ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
                verify(bookingRepository).save(saved.capture());
                assertThat(saved.getValue().getOrigin())
                        .isEqualTo(com.skbingegalaxy.booking.domain.BookingOrigin.CHANNEL);
                assertThat(saved.getValue().getExternalSource()).isEqualTo("acme-channel");
                assertThat(saved.getValue().getExternalRef()).isEqualTo("ACME-1");
                // No SK account exists for a channel guest: attributed to the same
                // "no known customer" id that admin walk-ins already use.
                assertThat(saved.getValue().getCustomerId()).isZero();
                assertThat(saved.getValue().getCustomerName()).isEqualTo("Channel Guest");
        }

        @Test
        void ingestChannelReservation_skipsCustomerFunnelGuards() {
                // THE point of G3. Every channel reservation shares customerId = 0, so an
                // unpaid-limit or freeze check would begin rejecting unrelated PAID
                // reservations as soon as two were pending at once — and nothing would log
                // an error. The venue would simply never receive the booking.
                stubChannelHappyPath("acme-channel", "ACME-2");

                bookingService.ingestChannelReservation(channelRequest("acme-channel", "ACME-2"));

                verify(bookingRepository).save(any(Booking.class));
                // Asserted as "never called" rather than stubbed-and-ignored: the guards
                // must not merely pass, they must not be consulted at all. Every channel
                // reservation shares customerId = 0, so even reading those counters would
                // couple unrelated venues' reservations to each other.
                verify(bookingRepository, never()).countPendingByCustomerIdAndBingeId(anyLong(), anyLong());
                verify(bookingRepository, never()).countRecentTimeoutCancellationsByBinge(anyLong(), anyLong(), any());
                verify(customerFreezeService, never()).assertNotFrozen(anyLong(), anyLong());
                verify(bookingRepository, never()).existsPendingDuplicate(anyLong(), anyLong(), any(), any());
        }

        @Test
        void ingestChannelReservation_isIdempotentAcrossRedelivery() {
                // Channels retry. A redelivered reservation must converge on the booking
                // created the first time rather than erroring or double-booking. Source is
                // matched case-insensitively and trimmed, so 'ACME-Channel' is the same
                // channel as 'acme-channel' — otherwise the unique index would happily
                // store both and the duplicate check would miss.
                testBooking.setExternalSource("acme-channel");
                testBooking.setExternalRef("ACME-3");
                when(bookingRepository.findByBingeIdAndExternalSourceAndExternalRef(11L, "acme-channel", "ACME-3"))
                        .thenReturn(Optional.of(testBooking));

                BookingDto result = bookingService.ingestChannelReservation(
                        channelRequest("  ACME-Channel  ", "  ACME-3  "));

                assertThat(result.getBookingRef()).isEqualTo(testBooking.getBookingRef());
                verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        void ingestChannelReservation_restoresCallerBingeContext() {
                // Ingestion sets BingeContext so binge-scoped queries resolve. On a pooled
                // request thread, leaking that id into the next request is a tenancy bug.
                BingeContext.setBingeId(777L);
                testBooking.setExternalSource("acme-channel");
                testBooking.setExternalRef("ACME-4");
                when(bookingRepository.findByBingeIdAndExternalSourceAndExternalRef(anyLong(), anyString(), anyString()))
                        .thenReturn(Optional.of(testBooking));

                bookingService.ingestChannelReservation(channelRequest("acme-channel", "ACME-4"));

                assertThat(BingeContext.getBingeId())
                        .as("the caller's binge context must survive ingestion")
                        .isEqualTo(777L);
        }

        // ── V90: the venue is part of a channel reservation's identity ─────

        @Test
        void cancelChannelReservation_isScopedToTheVenue() {
                // externalSource is a destination slug every venue on that destination
                // shares, and externalRef is chosen by the reseller. Looked up on those
                // two alone, one venue's cancellation resolved to whichever venue had
                // used the reference first — and cancelled ITS booking, with both sides
                // reporting success.
                when(bookingRepository.findByBingeIdAndExternalSourceAndExternalRef(11L, "acme-channel", "ACME-9"))
                        .thenReturn(Optional.empty());

                assertThatThrownBy(() -> bookingService.cancelChannelReservation(
                                11L, "acme-channel", "ACME-9", "traveller cancelled"))
                        .isInstanceOf(com.skbingegalaxy.common.exception.ResourceNotFoundException.class);

                // The venue is in the query, so another venue's row cannot answer it.
                verify(bookingRepository).findByBingeIdAndExternalSourceAndExternalRef(
                        11L, "acme-channel", "ACME-9");
        }

        // ── The confirmation that used to be a no-op ───────────────────────

        private Booking channelBooking(BookingStatus status) {
                testBooking.setOrigin(com.skbingegalaxy.booking.domain.BookingOrigin.CHANNEL);
                testBooking.setExternalSource("acme-channel");
                testBooking.setExternalRef("ACME-5");
                testBooking.setBingeId(11L);
                testBooking.setStatus(status);
                return testBooking;
        }

        @Test
        void confirmChannelReservation_confirmsThePendingBooking() {
                // THE BUG. A channel reservation arrives as an unpaid PENDING booking. The
                // reseller then confirms, having taken the traveller's money on its own
                // side — and nothing acted on it. The booking stayed PENDING, so the
                // pending-timeout sweep auto-cancelled it about half an hour later. The
                // reseller held a confirmation, the traveller held a receipt, and the
                // venue's calendar quietly emptied.
                when(bookingRepository.findByBingeIdAndExternalSourceAndExternalRef(11L, "acme-channel", "ACME-5"))
                        .thenReturn(Optional.of(channelBooking(BookingStatus.PENDING)));
                when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

                BookingService.ChannelConfirmResult result =
                        bookingService.confirmChannelReservation(11L, "acme-channel", "ACME-5");

                assertThat(result.confirmed()).isTrue();
                ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
                verify(bookingRepository).save(saved.capture());
                assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
                assertThat(saved.getValue().getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
                // SK Binge collected nothing — the money went to the channel. Adding it
                // here would inflate every revenue report by money that never arrives in
                // the venue's account; what the channel owes is a receivable and lives in
                // the distribution context's settlement records.
                assertThat(saved.getValue().getCollectedAmount())
                        .satisfiesAnyOf(c -> assertThat(c).isNull(),
                                        c -> assertThat(c).isEqualByComparingTo(BigDecimal.ZERO));
        }

        @Test
        void confirmChannelReservation_isIdempotentAcrossRedelivery() {
                // At-least-once delivery guarantees the retry, and erroring on it would
                // trap the message in the channel's own retry loop.
                when(bookingRepository.findByBingeIdAndExternalSourceAndExternalRef(11L, "acme-channel", "ACME-5"))
                        .thenReturn(Optional.of(channelBooking(BookingStatus.CONFIRMED)));

                BookingService.ChannelConfirmResult result =
                        bookingService.confirmChannelReservation(11L, "acme-channel", "ACME-5");

                assertThat(result.confirmed()).isFalse();
                assertThat(result.detail()).contains("Already");
                verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        void confirmChannelReservation_refusesAnExpiredHold() {
                when(bookingRepository.findByBingeIdAndExternalSourceAndExternalRef(11L, "acme-channel", "ACME-5"))
                        .thenReturn(Optional.of(channelBooking(BookingStatus.CANCELLED)));

                // Refused rather than forced: the hold expired and the slot may since have
                // been sold to someone else, so confirming anyway would double-book the
                // venue. The reseller gets a reason it can act on.
                assertThatThrownBy(() -> bookingService.confirmChannelReservation(
                                11L, "acme-channel", "ACME-5"))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("no longer be confirmed");
                verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        void createBooking_directOrigin_stillAppliesCustomerFunnelGuards() {
                // The other half of G3: scoping the guards must not weaken them for the
                // path they were written for.
                BingeContext.setBingeId(11L);
                // No event-type stub on purpose: the funnel guard must reject BEFORE the
                // event type is even resolved, so stubbing it would be dead setup.
                when(bookingRepository.countPendingByCustomerIdAndBingeId(anyLong(), anyLong())).thenReturn(99L);

                CreateBookingRequest request = CreateBookingRequest.builder()
                        .eventTypeId(1L)
                        .bookingDate(LocalDate.now().plusDays(7))
                        .startTime(LocalTime.of(14, 0))
                        .durationHours(3)
                        .build();

                assertThatThrownBy(() -> bookingService.createBooking(
                                request, 1L, "John Doe", "john@example.com", "9876543210"))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("unpaid booking");
        }
}
