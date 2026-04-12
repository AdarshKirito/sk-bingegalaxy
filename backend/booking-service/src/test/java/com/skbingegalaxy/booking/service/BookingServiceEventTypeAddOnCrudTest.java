package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for per-binge event type and add-on CRUD,
 * covering worst-case scoping, delete guards, and edge cases.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceEventTypeAddOnCrudTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingAddOnRepository bookingAddOnRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private RateCodeEventPricingRepository rateCodeEventPricingRepository;
    @Mock private RateCodeAddonPricingRepository rateCodeAddonPricingRepository;
    @Mock private CustomerEventPricingRepository customerEventPricingRepository;
    @Mock private CustomerAddonPricingRepository customerAddonPricingRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private SystemSettingsService systemSettingsService;
    @Mock private PricingService pricingService;
    @Mock private BookingEventLogService eventLogService;
    @Mock private SagaOrchestrator sagaOrchestrator;
    @Mock private com.skbingegalaxy.booking.client.AvailabilityClient availabilityClient;
    @Spy  private com.skbingegalaxy.booking.client.AvailabilityClientFallback availabilityFallback;
    @Mock private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks private BookingService bookingService;

    @BeforeEach
    void setUp() {
        BingeContext.clear();
        ReflectionTestUtils.setField(bookingService, "availabilityClient", availabilityClient);
        ReflectionTestUtils.setField(bookingService, "internalApiSecret", "test-secret");
        ReflectionTestUtils.setField(bookingService, "refPrefix", "SKBG");
        ReflectionTestUtils.setField(bookingService, "maxPendingPerCustomer", 2);
        ReflectionTestUtils.setField(bookingService, "cooldownMinutesAfterTimeout", 10);
    }

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    // ── Event Type CRUD ─────────────────────────────────────

    @Nested
    @DisplayName("Create Event Type")
    class CreateEventTypeTests {

        @Test
        @DisplayName("creates binge-scoped event type")
        void createEventType_assignsBingeId() {
            BingeContext.setBingeId(11L);
            EventTypeSaveRequest req = new EventTypeSaveRequest();
            req.setName("Kids Party");
            req.setDescription("Fun party for kids");
            req.setBasePrice(BigDecimal.valueOf(1500));
            req.setHourlyRate(BigDecimal.valueOf(300));
            req.setMinHours(2);
            req.setMaxHours(4);

            when(eventTypeRepository.save(any(EventType.class))).thenAnswer(inv -> {
                EventType et = inv.getArgument(0);
                et.setId(10L);
                return et;
            });

            EventTypeDto result = bookingService.createEventType(req);

            ArgumentCaptor<EventType> captor = ArgumentCaptor.forClass(EventType.class);
            verify(eventTypeRepository).save(captor.capture());
            assertThat(captor.getValue().getBingeId()).isEqualTo(11L);
            assertThat(captor.getValue().getName()).isEqualTo("Kids Party");
            assertThat(captor.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("rejects creation without binge context")
        void createEventType_noBinge_throws() {
            EventTypeSaveRequest req = new EventTypeSaveRequest();
            req.setName("Test");
            req.setBasePrice(BigDecimal.TEN);
            req.setHourlyRate(BigDecimal.ONE);

            assertThatThrownBy(() -> bookingService.createEventType(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }

        @Test
        @DisplayName("defaults pricePerGuest to zero when null")
        void createEventType_nullPricePerGuest_defaultsToZero() {
            BingeContext.setBingeId(11L);
            EventTypeSaveRequest req = new EventTypeSaveRequest();
            req.setName("Simple");
            req.setBasePrice(BigDecimal.valueOf(1000));
            req.setHourlyRate(BigDecimal.valueOf(200));
            req.setPricePerGuest(null);
            req.setMinHours(1);
            req.setMaxHours(3);

            when(eventTypeRepository.save(any(EventType.class))).thenAnswer(inv -> {
                EventType et = inv.getArgument(0);
                et.setId(20L);
                return et;
            });

            bookingService.createEventType(req);

            ArgumentCaptor<EventType> captor = ArgumentCaptor.forClass(EventType.class);
            verify(eventTypeRepository).save(captor.capture());
            assertThat(captor.getValue().getPricePerGuest()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("handles null imageUrls gracefully")
        void createEventType_nullImageUrls_usesEmptyList() {
            BingeContext.setBingeId(11L);
            EventTypeSaveRequest req = new EventTypeSaveRequest();
            req.setName("No Images");
            req.setBasePrice(BigDecimal.valueOf(500));
            req.setHourlyRate(BigDecimal.valueOf(100));
            req.setImageUrls(null);
            req.setMinHours(1);
            req.setMaxHours(2);

            when(eventTypeRepository.save(any(EventType.class))).thenAnswer(inv -> {
                EventType et = inv.getArgument(0);
                et.setId(30L);
                return et;
            });

            bookingService.createEventType(req);

            ArgumentCaptor<EventType> captor = ArgumentCaptor.forClass(EventType.class);
            verify(eventTypeRepository).save(captor.capture());
            assertThat(captor.getValue().getImageUrls()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("Update Event Type")
    class UpdateEventTypeTests {

        @Test
        @DisplayName("updates all fields correctly")
        void updateEventType_updatesAllFields() {
            BingeContext.setBingeId(11L);
            EventType existing = EventType.builder()
                .id(1L).bingeId(11L).name("Old Name")
                .basePrice(BigDecimal.valueOf(1000))
                .hourlyRate(BigDecimal.valueOf(200))
                .minHours(2).maxHours(4)
                .imageUrls(new ArrayList<>(List.of("/old-img.jpg")))
                .active(true).build();

            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(existing));
            when(eventTypeRepository.save(any(EventType.class))).thenAnswer(inv -> inv.getArgument(0));

            EventTypeSaveRequest req = new EventTypeSaveRequest();
            req.setName("New Name");
            req.setDescription("New Desc");
            req.setBasePrice(BigDecimal.valueOf(2000));
            req.setHourlyRate(BigDecimal.valueOf(400));
            req.setPricePerGuest(BigDecimal.valueOf(50));
            req.setMinHours(1);
            req.setMaxHours(6);
            req.setImageUrls(List.of("/new-img.png"));

            EventTypeDto result = bookingService.updateEventType(1L, req);

            assertThat(existing.getName()).isEqualTo("New Name");
            assertThat(existing.getBasePrice()).isEqualByComparingTo(BigDecimal.valueOf(2000));
            assertThat(existing.getImageUrls()).containsExactly("/new-img.png");
        }

        @Test
        @DisplayName("rejects update for event type from different binge")
        void updateEventType_foreignBinge_throws() {
            BingeContext.setBingeId(11L);
            when(eventTypeRepository.findByIdAndBingeId(99L, 11L)).thenReturn(Optional.empty());

            EventTypeSaveRequest req = new EventTypeSaveRequest();
            req.setName("Hacked");
            req.setBasePrice(BigDecimal.ONE);
            req.setHourlyRate(BigDecimal.ONE);

            assertThatThrownBy(() -> bookingService.updateEventType(99L, req))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Delete Event Type")
    class DeleteEventTypeTests {

        @Test
        @DisplayName("deletes inactive event type with no references")
        void deleteEventType_success() {
            BingeContext.setBingeId(11L);
            EventType et = EventType.builder()
                .id(1L).bingeId(11L).name("Deletable").active(false).build();

            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(et));
            when(bookingRepository.existsByEventTypeId(1L)).thenReturn(false);
            when(rateCodeEventPricingRepository.existsByEventTypeId(1L)).thenReturn(false);
            when(customerEventPricingRepository.existsByEventTypeId(1L)).thenReturn(false);

            bookingService.deleteEventType(1L);

            verify(eventTypeRepository).delete(et);
        }

        @Test
        @DisplayName("rejects delete of active event type")
        void deleteEventType_active_throws() {
            BingeContext.setBingeId(11L);
            EventType et = EventType.builder()
                .id(1L).bingeId(11L).name("Active").active(true).build();

            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(et));

            assertThatThrownBy(() -> bookingService.deleteEventType(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Deactivate the event type");
        }

        @Test
        @DisplayName("rejects delete when bookings exist")
        void deleteEventType_hasBookings_throws() {
            BingeContext.setBingeId(11L);
            EventType et = EventType.builder()
                .id(1L).bingeId(11L).name("Used").active(false).build();

            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(et));
            when(bookingRepository.existsByEventTypeId(1L)).thenReturn(true);

            assertThatThrownBy(() -> bookingService.deleteEventType(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already used in bookings");
        }

        @Test
        @DisplayName("rejects delete when rate code pricing references exist")
        void deleteEventType_hasRateCodePricing_throws() {
            BingeContext.setBingeId(11L);
            EventType et = EventType.builder()
                .id(1L).bingeId(11L).name("RateRef").active(false).build();

            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(et));
            when(bookingRepository.existsByEventTypeId(1L)).thenReturn(false);
            when(rateCodeEventPricingRepository.existsByEventTypeId(1L)).thenReturn(true);

            assertThatThrownBy(() -> bookingService.deleteEventType(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rate codes still reference");
        }

        @Test
        @DisplayName("rejects delete when customer pricing references exist")
        void deleteEventType_hasCustomerPricing_throws() {
            BingeContext.setBingeId(11L);
            EventType et = EventType.builder()
                .id(1L).bingeId(11L).name("CustRef").active(false).build();

            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(et));
            when(bookingRepository.existsByEventTypeId(1L)).thenReturn(false);
            when(rateCodeEventPricingRepository.existsByEventTypeId(1L)).thenReturn(false);
            when(customerEventPricingRepository.existsByEventTypeId(1L)).thenReturn(true);

            assertThatThrownBy(() -> bookingService.deleteEventType(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("customer pricing profiles still reference");
        }

        @Test
        @DisplayName("rejects delete from different binge")
        void deleteEventType_foreignBinge_throws() {
            BingeContext.setBingeId(11L);
            when(eventTypeRepository.findByIdAndBingeId(99L, 11L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.deleteEventType(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Deactivate Event Type")
    class DeactivateEventTypeTests {

        @Test
        @DisplayName("toggles active to inactive")
        void deactivateEventType_togglesOff() {
            BingeContext.setBingeId(11L);
            EventType et = EventType.builder()
                .id(1L).bingeId(11L).name("Toggler").active(true).build();

            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(et));
            when(eventTypeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            bookingService.deactivateEventType(1L);

            assertThat(et.isActive()).isFalse();
            verify(eventTypeRepository).save(et);
        }

        @Test
        @DisplayName("toggles inactive to active")
        void deactivateEventType_togglesOn() {
            BingeContext.setBingeId(11L);
            EventType et = EventType.builder()
                .id(1L).bingeId(11L).name("Toggler").active(false).build();

            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(et));
            when(eventTypeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            bookingService.deactivateEventType(1L);

            assertThat(et.isActive()).isTrue();
        }
    }

    // ── Add-On CRUD ──────────────────────────────────────────

    @Nested
    @DisplayName("Create Add-On")
    class CreateAddOnTests {

        @Test
        @DisplayName("creates binge-scoped add-on with uppercased category")
        void createAddOn_assignsBingeId() {
            BingeContext.setBingeId(11L);
            AddOnSaveRequest req = new AddOnSaveRequest();
            req.setName("DJ Setup");
            req.setDescription("Full DJ system");
            req.setPrice(BigDecimal.valueOf(2000));
            req.setCategory("experience");

            when(addOnRepository.save(any(AddOn.class))).thenAnswer(inv -> {
                AddOn a = inv.getArgument(0);
                a.setId(50L);
                return a;
            });

            bookingService.createAddOn(req);

            ArgumentCaptor<AddOn> captor = ArgumentCaptor.forClass(AddOn.class);
            verify(addOnRepository).save(captor.capture());
            assertThat(captor.getValue().getBingeId()).isEqualTo(11L);
            assertThat(captor.getValue().getCategory()).isEqualTo("EXPERIENCE");
            assertThat(captor.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("rejects creation without binge context")
        void createAddOn_noBinge_throws() {
            AddOnSaveRequest req = new AddOnSaveRequest();
            req.setName("Test");
            req.setPrice(BigDecimal.TEN);
            req.setCategory("FOOD");

            assertThatThrownBy(() -> bookingService.createAddOn(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }
    }

    @Nested
    @DisplayName("Delete Add-On")
    class DeleteAddOnTests {

        @Test
        @DisplayName("deletes inactive add-on with no references")
        void deleteAddOn_success() {
            BingeContext.setBingeId(11L);
            AddOn addOn = AddOn.builder()
                .id(5L).bingeId(11L).name("Deletable").active(false).price(BigDecimal.TEN).category("FOOD").build();

            when(addOnRepository.findByIdAndBingeId(5L, 11L)).thenReturn(Optional.of(addOn));
            when(bookingAddOnRepository.existsByAddOnId(5L)).thenReturn(false);
            when(rateCodeAddonPricingRepository.existsByAddOnId(5L)).thenReturn(false);
            when(customerAddonPricingRepository.existsByAddOnId(5L)).thenReturn(false);

            bookingService.deleteAddOn(5L);

            verify(addOnRepository).delete(addOn);
        }

        @Test
        @DisplayName("rejects delete of active add-on")
        void deleteAddOn_active_throws() {
            BingeContext.setBingeId(11L);
            AddOn addOn = AddOn.builder()
                .id(5L).bingeId(11L).name("Active").active(true).price(BigDecimal.TEN).category("FOOD").build();

            when(addOnRepository.findByIdAndBingeId(5L, 11L)).thenReturn(Optional.of(addOn));

            assertThatThrownBy(() -> bookingService.deleteAddOn(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Deactivate the add-on");
        }

        @Test
        @DisplayName("rejects delete when bookings reference the add-on")
        void deleteAddOn_hasBookings_throws() {
            BingeContext.setBingeId(11L);
            AddOn addOn = AddOn.builder()
                .id(5L).bingeId(11L).name("Used").active(false).price(BigDecimal.TEN).category("FOOD").build();

            when(addOnRepository.findByIdAndBingeId(5L, 11L)).thenReturn(Optional.of(addOn));
            when(bookingAddOnRepository.existsByAddOnId(5L)).thenReturn(true);

            assertThatThrownBy(() -> bookingService.deleteAddOn(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already used in bookings");
        }

        @Test
        @DisplayName("rejects delete when rate code pricing references exist")
        void deleteAddOn_hasRateCodePricing_throws() {
            BingeContext.setBingeId(11L);
            AddOn addOn = AddOn.builder()
                .id(5L).bingeId(11L).name("RateRef").active(false).price(BigDecimal.TEN).category("FOOD").build();

            when(addOnRepository.findByIdAndBingeId(5L, 11L)).thenReturn(Optional.of(addOn));
            when(bookingAddOnRepository.existsByAddOnId(5L)).thenReturn(false);
            when(rateCodeAddonPricingRepository.existsByAddOnId(5L)).thenReturn(true);

            assertThatThrownBy(() -> bookingService.deleteAddOn(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rate codes still reference");
        }

        @Test
        @DisplayName("rejects delete when customer pricing references exist")
        void deleteAddOn_hasCustomerPricing_throws() {
            BingeContext.setBingeId(11L);
            AddOn addOn = AddOn.builder()
                .id(5L).bingeId(11L).name("CustRef").active(false).price(BigDecimal.TEN).category("FOOD").build();

            when(addOnRepository.findByIdAndBingeId(5L, 11L)).thenReturn(Optional.of(addOn));
            when(bookingAddOnRepository.existsByAddOnId(5L)).thenReturn(false);
            when(rateCodeAddonPricingRepository.existsByAddOnId(5L)).thenReturn(false);
            when(customerAddonPricingRepository.existsByAddOnId(5L)).thenReturn(true);

            assertThatThrownBy(() -> bookingService.deleteAddOn(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("customer pricing profiles still reference");
        }
    }

    // ── Listing Tests ────────────────────────────────────────

    @Nested
    @DisplayName("Binge-Scoped Listing")
    class ListingTests {

        @Test
        @DisplayName("getActiveEventTypes only returns binge-scoped items")
        void getActiveEventTypes_bingeScoped() {
            BingeContext.setBingeId(11L);
            EventType et = EventType.builder().id(1L).bingeId(11L).name("Local").active(true).build();
            when(eventTypeRepository.findByBingeIdAndActiveTrue(11L)).thenReturn(List.of(et));

            List<EventTypeDto> result = bookingService.getActiveEventTypes();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("Local");
            verify(eventTypeRepository, never()).findByBingeIdOrGlobalAndActiveTrue(anyLong());
        }

        @Test
        @DisplayName("getAllEventTypes returns all for binge including inactive")
        void getAllEventTypes_returnsAllForBinge() {
            BingeContext.setBingeId(11L);
            when(eventTypeRepository.findByBingeId(11L)).thenReturn(List.of(
                EventType.builder().id(1L).bingeId(11L).name("Active").active(true).build(),
                EventType.builder().id(2L).bingeId(11L).name("Inactive").active(false).build()
            ));

            List<EventTypeDto> result = bookingService.getAllEventTypes();

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("getActiveAddOns only returns binge-scoped items")
        void getActiveAddOns_bingeScoped() {
            BingeContext.setBingeId(11L);
            AddOn a = AddOn.builder().id(1L).bingeId(11L).name("Local Addon").active(true).price(BigDecimal.TEN).category("FOOD").build();
            when(addOnRepository.findByBingeIdAndActiveTrue(11L)).thenReturn(List.of(a));

            List<AddOnDto> result = bookingService.getActiveAddOns();

            assertThat(result).hasSize(1);
            verify(addOnRepository, never()).findByBingeIdOrGlobalAndActiveTrue(anyLong());
        }

        @Test
        @DisplayName("listing without binge context throws")
        void listEventTypes_noBinge_throws() {
            assertThatThrownBy(() -> bookingService.getActiveEventTypes())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }

        @Test
        @DisplayName("returns empty list when binge has no event types")
        void getActiveEventTypes_empty() {
            BingeContext.setBingeId(11L);
            when(eventTypeRepository.findByBingeIdAndActiveTrue(11L)).thenReturn(List.of());

            List<EventTypeDto> result = bookingService.getActiveEventTypes();

            assertThat(result).isEmpty();
        }
    }
}
