package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for PricingService: rate code CRUD, customer pricing,
 * pricing resolution chain, delete guards, and per-binge scoping.
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceComprehensiveTest {

    @Mock private RateCodeRepository rateCodeRepository;
    @Mock private CustomerPricingProfileRepository customerPricingProfileRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private RateCodeChangeLogRepository rateCodeChangeLogRepository;
    @Mock private BookingRepository bookingRepository;

    @InjectMocks private PricingService pricingService;

    private EventType birthdayParty;
    private AddOn djSetup;
    private RateCode vipCode;

    @BeforeEach
    void setUp() {
        BingeContext.clear();

        birthdayParty = EventType.builder()
            .id(1L).bingeId(11L).name("Birthday Party")
            .basePrice(BigDecimal.valueOf(2000))
            .hourlyRate(BigDecimal.valueOf(500))
            .pricePerGuest(BigDecimal.valueOf(50))
            .active(true).build();

        djSetup = AddOn.builder()
            .id(10L).bingeId(11L).name("DJ Setup")
            .price(BigDecimal.valueOf(2000))
            .category("EXPERIENCE").active(true).build();

        vipCode = RateCode.builder()
            .id(7L).bingeId(11L).name("VIP").description("VIP Pricing")
            .active(true)
            .eventPricings(new ArrayList<>())
            .addonPricings(new ArrayList<>())
            .build();
    }

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    // ── Rate Code CRUD ───────────────────────────────────────

    @Nested
    @DisplayName("Rate Code CRUD")
    class RateCodeCrudTests {

        @Test
        @DisplayName("creates rate code scoped to binge")
        void createRateCode_stampsSelectedBinge() {
            BingeContext.setBingeId(11L);
            when(rateCodeRepository.existsByNameAndBingeId("CORPORATE", 11L)).thenReturn(false);
            when(rateCodeRepository.save(any(RateCode.class))).thenAnswer(inv -> {
                RateCode rc = inv.getArgument(0);
                rc.setId(20L);
                rc.setEventPricings(new ArrayList<>());
                rc.setAddonPricings(new ArrayList<>());
                return rc;
            });

            RateCodeSaveRequest req = RateCodeSaveRequest.builder()
                .name("CORPORATE").description("Corporate rate")
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

            RateCodeDto result = pricingService.createRateCode(req);

            ArgumentCaptor<RateCode> captor = ArgumentCaptor.forClass(RateCode.class);
            verify(rateCodeRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues().get(0).getBingeId()).isEqualTo(11L);
            assertThat(captor.getAllValues().get(0).isActive()).isTrue();
        }

        @Test
        @DisplayName("rejects duplicate rate code name within same binge")
        void createRateCode_duplicateName_throws() {
            BingeContext.setBingeId(11L);
            when(rateCodeRepository.existsByNameAndBingeId("VIP", 11L)).thenReturn(true);

            RateCodeSaveRequest req = RateCodeSaveRequest.builder()
                .name("VIP").description("Duplicate")
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

            assertThatThrownBy(() -> pricingService.createRateCode(req))
                .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("rejects rate code creation without binge context")
        void createRateCode_noBinge_throws() {
            RateCodeSaveRequest req = RateCodeSaveRequest.builder()
                .name("TEST").description("desc")
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>()).build();

            assertThatThrownBy(() -> pricingService.createRateCode(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }

        @Test
        @DisplayName("toggles rate code active state")
        void toggleRateCode_togglesActiveFlag() {
            BingeContext.setBingeId(11L);
            when(rateCodeRepository.findByIdAndBingeId(7L, 11L)).thenReturn(Optional.of(vipCode));
            when(rateCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(vipCode.isActive()).isTrue();
            pricingService.toggleRateCode(7L);
            assertThat(vipCode.isActive()).isFalse();
        }
    }

    // ── Rate Code Delete Guards ──────────────────────────────

    @Nested
    @DisplayName("Rate Code Delete Guards")
    class RateCodeDeleteTests {

        @Test
        @DisplayName("deletes inactive rate code with no references")
        void deleteRateCode_inactiveNoRefs_success() {
            BingeContext.setBingeId(11L);
            vipCode.setActive(false);
            when(rateCodeRepository.findByIdAndBingeId(7L, 11L)).thenReturn(Optional.of(vipCode));
            when(customerPricingProfileRepository.existsByRateCodeId(7L)).thenReturn(false);

            pricingService.deleteRateCode(7L);

            verify(rateCodeRepository).delete(vipCode);
        }

        @Test
        @DisplayName("rejects delete of active rate code")
        void deleteRateCode_active_throws() {
            BingeContext.setBingeId(11L);
            when(rateCodeRepository.findByIdAndBingeId(7L, 11L)).thenReturn(Optional.of(vipCode));

            assertThatThrownBy(() -> pricingService.deleteRateCode(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Deactivate the rate code");
        }

        @Test
        @DisplayName("rejects delete when customer profiles reference it")
        void deleteRateCode_hasCustomerProfiles_throws() {
            BingeContext.setBingeId(11L);
            vipCode.setActive(false);
            when(rateCodeRepository.findByIdAndBingeId(7L, 11L)).thenReturn(Optional.of(vipCode));
            when(customerPricingProfileRepository.existsByRateCodeId(7L)).thenReturn(true);

            assertThatThrownBy(() -> pricingService.deleteRateCode(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("customer pricing profiles still use it");
        }

        @Test
        @DisplayName("rejects accessing rate code from different binge")
        void deleteRateCode_foreignBinge_throws() {
            BingeContext.setBingeId(99L);
            when(rateCodeRepository.findByIdAndBingeId(7L, 99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pricingService.deleteRateCode(7L))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── Pricing Resolution Chain ─────────────────────────────

    @Nested
    @DisplayName("Pricing Resolution Chain")
    class ResolutionTests {

        @Test
        @DisplayName("resolves DEFAULT pricing when no customer profile exists")
        void resolveEventPrice_noProfile_returnsDefault() {
            BingeContext.setBingeId(11L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(birthdayParty));
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(99L, 11L))
                .thenReturn(Optional.empty());

            PricingService.ResolvedEventPrice result = pricingService.resolveEventPrice(99L, 1L);

            assertThat(result.source()).isEqualTo("DEFAULT");
            assertThat(result.basePrice()).isEqualByComparingTo(BigDecimal.valueOf(2000));
            assertThat(result.hourlyRate()).isEqualByComparingTo(BigDecimal.valueOf(500));
            assertThat(result.rateCodeName()).isNull();
        }

        @Test
        @DisplayName("resolves RATE_CODE pricing when profile has rate code")
        void resolveEventPrice_rateCode_usesRateCodePricing() {
            BingeContext.setBingeId(11L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(birthdayParty));

            RateCodeEventPricing rcPricing = RateCodeEventPricing.builder()
                .eventType(birthdayParty)
                .basePrice(BigDecimal.valueOf(1500))
                .hourlyRate(BigDecimal.valueOf(400))
                .pricePerGuest(BigDecimal.valueOf(30))
                .build();
            vipCode.getEventPricings().add(rcPricing);

            CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(2L).bingeId(11L).rateCode(vipCode)
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.of(profile));

            PricingService.ResolvedEventPrice result = pricingService.resolveEventPrice(2L, 1L);

            assertThat(result.source()).isEqualTo("RATE_CODE");
            assertThat(result.basePrice()).isEqualByComparingTo(BigDecimal.valueOf(1500));
            assertThat(result.rateCodeName()).isEqualTo("VIP");
        }

        @Test
        @DisplayName("CUSTOMER pricing overrides RATE_CODE")
        void resolveEventPrice_customerOverride_winsOverRateCode() {
            BingeContext.setBingeId(11L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(birthdayParty));

            // Rate code pricing exists
            RateCodeEventPricing rcPricing = RateCodeEventPricing.builder()
                .eventType(birthdayParty)
                .basePrice(BigDecimal.valueOf(1500))
                .hourlyRate(BigDecimal.valueOf(400))
                .pricePerGuest(BigDecimal.valueOf(30))
                .build();
            vipCode.getEventPricings().add(rcPricing);

            // Customer-specific pricing also exists
            CustomerEventPricing custPricing = CustomerEventPricing.builder()
                .eventType(birthdayParty)
                .basePrice(BigDecimal.valueOf(1000))
                .hourlyRate(BigDecimal.valueOf(300))
                .pricePerGuest(BigDecimal.ZERO)
                .build();

            CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(2L).bingeId(11L).rateCode(vipCode)
                .eventPricings(new ArrayList<>(List.of(custPricing)))
                .addonPricings(new ArrayList<>())
                .build();

            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.of(profile));

            PricingService.ResolvedEventPrice result = pricingService.resolveEventPrice(2L, 1L);

            assertThat(result.source()).isEqualTo("CUSTOMER");
            assertThat(result.basePrice()).isEqualByComparingTo(BigDecimal.valueOf(1000));
            assertThat(result.rateCodeName()).isNull();
        }

        @Test
        @DisplayName("inactive rate code falls through to DEFAULT")
        void resolveEventPrice_inactiveRateCode_fallsToDefault() {
            BingeContext.setBingeId(11L);
            when(eventTypeRepository.findByIdAndBingeId(1L, 11L)).thenReturn(Optional.of(birthdayParty));

            vipCode.setActive(false);
            RateCodeEventPricing rcPricing = RateCodeEventPricing.builder()
                .eventType(birthdayParty)
                .basePrice(BigDecimal.valueOf(1500))
                .hourlyRate(BigDecimal.valueOf(400))
                .pricePerGuest(BigDecimal.valueOf(30))
                .build();
            vipCode.getEventPricings().add(rcPricing);

            CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(2L).bingeId(11L).rateCode(vipCode)
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.of(profile));

            PricingService.ResolvedEventPrice result = pricingService.resolveEventPrice(2L, 1L);

            assertThat(result.source()).isEqualTo("DEFAULT");
            assertThat(result.basePrice()).isEqualByComparingTo(BigDecimal.valueOf(2000));
        }

        @Test
        @DisplayName("resolves addon price with CUSTOMER override")
        void resolveAddonPrice_customerOverride() {
            BingeContext.setBingeId(11L);
            when(addOnRepository.findByIdAndBingeId(10L, 11L)).thenReturn(Optional.of(djSetup));

            CustomerAddonPricing custAddon = CustomerAddonPricing.builder()
                .addOn(djSetup).price(BigDecimal.valueOf(1500)).build();

            CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(2L).bingeId(11L)
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>(List.of(custAddon)))
                .build();

            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.of(profile));

            PricingService.ResolvedAddonPrice result = pricingService.resolveAddonPrice(2L, 10L);

            assertThat(result.source()).isEqualTo("CUSTOMER");
            assertThat(result.price()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        }

        @Test
        @DisplayName("resolves addon price via RATE_CODE")
        void resolveAddonPrice_rateCode() {
            BingeContext.setBingeId(11L);
            when(addOnRepository.findByIdAndBingeId(10L, 11L)).thenReturn(Optional.of(djSetup));

            RateCodeAddonPricing rcAddon = RateCodeAddonPricing.builder()
                .addOn(djSetup).price(BigDecimal.valueOf(1800)).build();
            vipCode.getAddonPricings().add(rcAddon);

            CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(2L).bingeId(11L).rateCode(vipCode)
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.of(profile));

            PricingService.ResolvedAddonPrice result = pricingService.resolveAddonPrice(2L, 10L);

            assertThat(result.source()).isEqualTo("RATE_CODE");
            assertThat(result.price()).isEqualByComparingTo(BigDecimal.valueOf(1800));
            assertThat(result.rateCodeName()).isEqualTo("VIP");
        }

        @Test
        @DisplayName("resolves addon price as DEFAULT when no overrides")
        void resolveAddonPrice_default() {
            BingeContext.setBingeId(11L);
            when(addOnRepository.findByIdAndBingeId(10L, 11L)).thenReturn(Optional.of(djSetup));
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(99L, 11L))
                .thenReturn(Optional.empty());

            PricingService.ResolvedAddonPrice result = pricingService.resolveAddonPrice(99L, 10L);

            assertThat(result.source()).isEqualTo("DEFAULT");
            assertThat(result.price()).isEqualByComparingTo(BigDecimal.valueOf(2000));
        }
    }

    // ── Customer Pricing CRUD ────────────────────────────────

    @Nested
    @DisplayName("Customer Pricing CRUD")
    class CustomerPricingCrudTests {

        @Test
        @DisplayName("returns empty DTO when no profile exists")
        void getCustomerPricing_noProfile_returnsEmpty() {
            BingeContext.setBingeId(11L);
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(99L, 11L))
                .thenReturn(Optional.empty());

            CustomerPricingDto dto = pricingService.getCustomerPricing(99L);

            assertThat(dto.getCustomerId()).isEqualTo(99L);
            assertThat(dto.getRateCodeId()).isNull();
            assertThat(dto.isScopedProfile()).isFalse();
        }

        @Test
        @DisplayName("save creates new profile scoped to binge")
        void saveCustomerPricing_createsScoped() {
            BingeContext.setBingeId(11L);
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.empty());
            when(customerPricingProfileRepository.save(any(CustomerPricingProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(customerPricingProfileRepository.saveAndFlush(any(CustomerPricingProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            CustomerPricingSaveRequest req = CustomerPricingSaveRequest.builder()
                .customerId(2L)
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

            CustomerPricingDto dto = pricingService.saveCustomerPricing(req);

            ArgumentCaptor<CustomerPricingProfile> cap = ArgumentCaptor.forClass(CustomerPricingProfile.class);
            verify(customerPricingProfileRepository, atLeastOnce()).save(cap.capture());
            assertThat(cap.getAllValues().get(0).getBingeId()).isEqualTo(11L);
        }

        @Test
        @DisplayName("delete customer pricing requires existing profile")
        void deleteCustomerPricing_notFound_throws() {
            BingeContext.setBingeId(11L);
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(99L, 11L))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> pricingService.deleteCustomerPricing(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("updates member label on existing profile")
        void updateMemberLabel_setsLabel() {
            BingeContext.setBingeId(11L);
            CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(2L).bingeId(11L).build();
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.of(profile));
            when(customerPricingProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            pricingService.updateMemberLabel(2L, "Gold Member");

            assertThat(profile.getMemberLabel()).isEqualTo("Gold Member");
        }

        @Test
        @DisplayName("blank member label clears to null")
        void updateMemberLabel_blank_clearsToNull() {
            BingeContext.setBingeId(11L);
            CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(2L).bingeId(11L).memberLabel("Old").build();
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.of(profile));
            when(customerPricingProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            pricingService.updateMemberLabel(2L, "   ");

            assertThat(profile.getMemberLabel()).isNull();
        }
    }

    // ── Bulk Rate Code Assignment ────────────────────────────

    @Nested
    @DisplayName("Bulk Rate Code Assignment")
    class BulkAssignTests {

        @Test
        @DisplayName("assigns rate code to multiple customers")
        void bulkAssign_multipleCustomers() {
            BingeContext.setBingeId(11L);
            when(rateCodeRepository.findByIdAndBingeId(7L, 11L)).thenReturn(Optional.of(vipCode));

            // Two customers, one existing and one new
            CustomerPricingProfile existing = CustomerPricingProfile.builder()
                .customerId(1L).bingeId(11L).build();
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(1L, 11L))
                .thenReturn(Optional.of(existing));
            when(customerPricingProfileRepository.findByCustomerIdAndBingeId(2L, 11L))
                .thenReturn(Optional.empty());
            when(customerPricingProfileRepository.save(any(CustomerPricingProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            BulkRateCodeAssignRequest req =
                BulkRateCodeAssignRequest.builder()
                    .rateCodeId(7L)
                    .customerIds(List.of(1L, 2L))
                    .build();

            int count = pricingService.bulkAssignRateCode(req);

            assertThat(count).isEqualTo(2);
            // 3 saves: 1 for creating customer 2's new profile + 2 for assigning rate code
            verify(customerPricingProfileRepository, times(3)).save(any(CustomerPricingProfile.class));
        }

        @Test
        @DisplayName("bulk assign without binge throws")
        void bulkAssign_noBinge_throws() {
            BulkRateCodeAssignRequest req = BulkRateCodeAssignRequest.builder()
                .rateCodeId(7L).customerIds(List.of(1L)).build();

            assertThatThrownBy(() -> pricingService.bulkAssignRateCode(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }
    }

    // ── RateCode Pricing Resolution ──────────────────────────

    @Nested
    @DisplayName("Rate Code Pricing Resolution")
    class RateCodePricingResolutionTests {

        @Test
        @DisplayName("resolves rate code pricing correctly")
        void resolveRateCodePricing_returnsRateAndDefaults() {
            BingeContext.setBingeId(11L);
            when(rateCodeRepository.findByIdAndBingeId(7L, 11L)).thenReturn(Optional.of(vipCode));
            when(eventTypeRepository.findByBingeIdAndActiveTrue(11L)).thenReturn(List.of(birthdayParty));
            when(addOnRepository.findByBingeIdAndActiveTrue(11L)).thenReturn(List.of(djSetup));

            // VIP has event pricing but no addon pricing
            RateCodeEventPricing rcEvent = RateCodeEventPricing.builder()
                .eventType(birthdayParty)
                .basePrice(BigDecimal.valueOf(1500))
                .hourlyRate(BigDecimal.valueOf(400))
                .pricePerGuest(BigDecimal.valueOf(40))
                .build();
            vipCode.getEventPricings().add(rcEvent);

            ResolvedPricingDto result = pricingService.resolveRateCodePricing(7L);

            assertThat(result.getPricingSource()).isEqualTo("RATE_CODE");
            assertThat(result.getRateCodeName()).isEqualTo("VIP");
            // Event pricing from rate code
            assertThat(result.getEventPricings()).hasSize(1);
            assertThat(result.getEventPricings().get(0).getSource()).isEqualTo("RATE_CODE");
            assertThat(result.getEventPricings().get(0).getBasePrice())
                .isEqualByComparingTo(BigDecimal.valueOf(1500));
            // Addon pricing defaults
            assertThat(result.getAddonPricings()).hasSize(1);
            assertThat(result.getAddonPricings().get(0).getSource()).isEqualTo("DEFAULT");
            assertThat(result.getAddonPricings().get(0).getPrice())
                .isEqualByComparingTo(BigDecimal.valueOf(2000));
        }

        @Test
        @DisplayName("rejects resolving inactive rate code pricing")
        void resolveRateCodePricing_inactive_throws() {
            BingeContext.setBingeId(11L);
            vipCode.setActive(false);
            when(rateCodeRepository.findByIdAndBingeId(7L, 11L)).thenReturn(Optional.of(vipCode));

            assertThatThrownBy(() -> pricingService.resolveRateCodePricing(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not active");
        }
    }

    // ── Binge Scoping ────────────────────────────────────────

    @Nested
    @DisplayName("Binge Scoping")
    class BingeScopingTests {

        @Test
        @DisplayName("getAllRateCodes only returns selected binge's codes")
        void getAllRateCodes_bingeScoped() {
            BingeContext.setBingeId(11L);
            when(rateCodeRepository.findByBingeId(11L)).thenReturn(List.of(vipCode));

            List<RateCodeDto> result = pricingService.getAllRateCodes();

            assertThat(result).hasSize(1);
            verify(rateCodeRepository).findByBingeId(11L);
        }

        @Test
        @DisplayName("getActiveRateCodes without binge throws")
        void getActiveRateCodes_noBinge_throws() {
            assertThatThrownBy(() -> pricingService.getActiveRateCodes())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Select a binge");
        }

        @Test
        @DisplayName("getRateCodeById from different binge throws")
        void getRateCodeById_differentBinge_throws() {
            BingeContext.setBingeId(99L);
            when(rateCodeRepository.findByIdAndBingeId(7L, 99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pricingService.getRateCodeById(7L))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
