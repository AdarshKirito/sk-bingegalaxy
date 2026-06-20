package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.BingeDto;
import com.skbingegalaxy.booking.dto.BingeSaveRequest;
import com.skbingegalaxy.booking.dto.PublicBingeDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardSlideDto;
import com.skbingegalaxy.booking.dto.CustomerAboutExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerAboutHighlightDto;
import com.skbingegalaxy.booking.dto.CustomerAboutPolicyDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CustomerPricingProfileRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.RateCodeRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BingeServiceTest {

    @Mock private BingeRepository bingeRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private RateCodeRepository rateCodeRepository;
    @Mock private CustomerPricingProfileRepository customerPricingProfileRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private AuthorityLockGuard authorityLockGuard;
    @Mock private VenueClockService venueClock;

    @InjectMocks private BingeService bingeService;

    @Test
    void deleteBinge_requiresInactiveStatus() {
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(true).build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));

        assertThatThrownBy(() -> bingeService.deleteBinge(7L, 11L, "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Deactivate the binge");

        verify(bingeRepository, never()).delete(binge);
    }

    @Test
    void deleteBinge_rejectsExistingBookings() {
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(false).build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));
        when(bookingRepository.existsByBingeId(7L)).thenReturn(true);

        assertThatThrownBy(() -> bingeService.deleteBinge(7L, 11L, "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already has bookings");

        verify(bingeRepository, never()).delete(binge);
    }

    @Test
    void getCustomerDashboardExperience_returnsDefaultsWhenMissing() {
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(true).build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));

        CustomerDashboardExperienceDto result = bingeService.getCustomerDashboardExperience(7L);

        assertThat(result.getLayout()).isEqualTo("GRID");
        assertThat(result.getSectionTitle()).isEqualTo("Pick a setup that matches the mood");
        assertThat(result.getSlides()).isEmpty();
    }

    @Test
    void updateCustomerDashboardExperience_normalizesAndPersistsSlides() {
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(true).build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));
        when(bingeRepository.save(any(Binge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerDashboardExperienceDto request = CustomerDashboardExperienceDto.builder()
            .sectionEyebrow("Curated Moments")
            .sectionTitle("Lead with your best setup")
            .layout("CAROUSEL")
            .slides(List.of(CustomerDashboardSlideDto.builder()
                .badge("Romance")
                .headline("Date-night takeover")
                .description("Lead with a quieter, more cinematic setup for proposals and anniversaries.")
                .ctaLabel("Build this mood")
                .theme("romance")
                .build()))
            .build();

        CustomerDashboardExperienceDto result = bingeService.updateCustomerDashboardExperience(7L, request, 11L, "ADMIN");

        assertThat(result.getLayout()).isEqualTo("CAROUSEL");
        assertThat(result.getSlides()).hasSize(1);
        assertThat(result.getSlides().get(0).getHeadline()).isEqualTo("Date-night takeover");
        assertThat(binge.getCustomerDashboardConfigJson()).contains("Date-night takeover");
    }

    @Test
    void updateCustomerAboutExperience_normalizesAndPersistsContent() {
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(true).build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));
        when(bingeRepository.save(any(Binge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerAboutExperienceDto request = CustomerAboutExperienceDto.builder()
            .sectionTitle("Know this venue")
            .heroTitle("Plan smoother events")
            .heroDescription("These policies and rules keep your event timeline stress-free.")
            .highlights(List.of(CustomerAboutHighlightDto.builder()
                .title("Private atmosphere")
                .description("Curated setups for birthdays, anniversaries, and screenings.")
                .build()))
            .houseRules(List.of("Arrive 15 minutes early"))
            .policies(List.of(CustomerAboutPolicyDto.builder()
                .title("Cancellation policy")
                .description("Cancellation availability depends on configured venue cutoff.")
                .build()))
            .build();

        CustomerAboutExperienceDto result = bingeService.updateCustomerAboutExperience(7L, request, 11L, "ADMIN");

        assertThat(result.getSectionTitle()).isEqualTo("Know this venue");
        assertThat(result.getHighlights()).hasSize(1);
        assertThat(result.getHouseRules()).contains("Arrive 15 minutes early");
        assertThat(result.getPolicies()).hasSize(1);
        assertThat(binge.getCustomerAboutConfigJson()).contains("Plan smoother events");
    }

    // ── Timezone-change gating wiring (the high-blast-radius branch) ─────────

    @Test
    void updateBinge_timezoneUnchanged_doesNotInvokeGuardOrEvict() {
        // Re-saving the SAME zone (every venue edit round-trips the field) must NOT
        // require the grant or flush the zone cache — otherwise routine edits 423.
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(true)
            .timezone("Asia/Kolkata").build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));
        when(bingeRepository.save(any(Binge.class))).thenAnswer(i -> i.getArgument(0));

        BingeSaveRequest req = new BingeSaveRequest();
        req.setName("Downtown");
        req.setTimezone("Asia/Kolkata");

        bingeService.updateBinge(7L, req, 11L, "ADMIN", false);

        verifyNoInteractions(authorityLockGuard);
        verify(venueClock, never()).evict(anyLong());
    }

    @Test
    void updateBinge_timezoneChanged_invokesGuard_appliesAndEvicts() {
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(true)
            .timezone("Asia/Kolkata").build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));
        when(bingeRepository.save(any(Binge.class))).thenAnswer(i -> i.getArgument(0));

        BingeSaveRequest req = new BingeSaveRequest();
        req.setName("Downtown");
        req.setTimezone("America/New_York");

        BingeDto dto = bingeService.updateBinge(7L, req, 11L, "ADMIN", false);

        verify(authorityLockGuard).requireTimezoneChangePermitted("ADMIN", false, 7L);
        verify(venueClock).evict(7L);
        assertThat(binge.getTimezone()).isEqualTo("America/New_York");
        assertThat(dto.getTimezone()).isEqualTo("America/New_York");
    }

    @Test
    void updateBinge_timezoneChanged_whenGuardDenies_propagatesAndDoesNotPersist() {
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(true)
            .timezone("Asia/Kolkata").build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));
        doThrow(new BusinessException("locked", HttpStatus.LOCKED))
            .when(authorityLockGuard).requireTimezoneChangePermitted("ADMIN", false, 7L);

        BingeSaveRequest req = new BingeSaveRequest();
        req.setName("Downtown");
        req.setTimezone("America/New_York");

        assertThatThrownBy(() -> bingeService.updateBinge(7L, req, 11L, "ADMIN", false))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);

        // The change is rolled back: zone untouched, nothing persisted, cache intact.
        assertThat(binge.getTimezone()).isEqualTo("Asia/Kolkata");
        verify(bingeRepository, never()).save(any(Binge.class));
        verify(venueClock, never()).evict(anyLong());
    }

    // ── Proximity discovery ("venues near me") ──────────────────────────────

    @Test
    void getNearbyBinges_ranksByDistance_skipsUngeocoded_andHonoursRadius() {
        Binge bangalore = Binge.builder().id(1L).name("Bengaluru").active(true)
            .latitude(12.9716).longitude(77.5946).build();
        Binge mysore = Binge.builder().id(2L).name("Mysuru").active(true)
            .latitude(12.2958).longitude(76.6394).build();
        Binge delhi = Binge.builder().id(3L).name("Delhi").active(true)
            .latitude(28.6139).longitude(77.2090).build(); // ~1740 km away
        Binge ungeocoded = Binge.builder().id(4L).name("No coords").active(true).build();
        when(bingeRepository.findCustomerVisibleBinges())
            .thenReturn(List.of(mysore, ungeocoded, delhi, bangalore));

        // Query from Bengaluru centre, 200 km radius.
        List<PublicBingeDto> result = bingeService.getNearbyBinges(12.9716, 77.5946, 200, 20);

        // Delhi (out of radius) and the un-geocoded venue are excluded; nearest first.
        assertThat(result).extracting(PublicBingeDto::getId).containsExactly(1L, 2L);
        assertThat(result.get(0).getDistanceKm()).isZero();
        assertThat(result.get(1).getDistanceKm()).isCloseTo(127.0, org.assertj.core.data.Offset.offset(3.0));
    }

    @Test
    void getNearbyBinges_appliesLimit() {
        Binge a = Binge.builder().id(1L).name("A").active(true).latitude(12.97).longitude(77.59).build();
        Binge b = Binge.builder().id(2L).name("B").active(true).latitude(12.98).longitude(77.60).build();
        when(bingeRepository.findCustomerVisibleBinges()).thenReturn(List.of(a, b));

        List<PublicBingeDto> result = bingeService.getNearbyBinges(12.97, 77.59, 500, 1);

        assertThat(result).hasSize(1).extracting(PublicBingeDto::getId).containsExactly(1L);
    }

    @Test
    void getBingeById_returnsCustomerSafeProjection() {
        // A binge with internal/admin fields populated...
        Binge binge = Binge.builder()
            .id(5L).name("Galaxy").adminId(99L)
            .supportEmail("help@example.com").timezone("Asia/Kolkata")
            .latitude(12.9).longitude(77.5)
            .approvalRejectionReason("internal-only reason")
            .freezeDurationMinutes(120)
            .build();
        when(bingeRepository.findById(5L)).thenReturn(Optional.of(binge));

        // ...is returned as the customer-safe projection. PublicBingeDto is structurally
        // incapable of carrying adminId / approval audit / freeze thresholds, so the
        // guarantee is enforced by the return type, not by runtime filtering. Changing
        // this back to the admin BingeDto would not compile.
        PublicBingeDto dto = bingeService.getBingeById(5L);
        assertThat(dto.getName()).isEqualTo("Galaxy");
        assertThat(dto.getSupportEmail()).isEqualTo("help@example.com");
        assertThat(dto.getLatitude()).isEqualTo(12.9);
        assertThat(dto.getTimezone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void getBingeById_hidesPendingVenue_with404() {
        // A pending (not-yet-approved) venue must not be readable by id — same 404 as
        // a missing id, so existence is never disclosed to an anonymous caller.
        Binge pending = Binge.builder().id(8L).name("Pending Venue").active(false)
            .status(BingeApprovalStatus.PENDING_APPROVAL).build();
        when(bingeRepository.findById(8L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> bingeService.getBingeById(8L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getBingeById_hidesDeactivatedVenue_with404() {
        // Deactivated (paused) but previously-approved venue is also hidden by id.
        Binge paused = Binge.builder().id(9L).name("Paused Venue").active(false)
            .status(BingeApprovalStatus.APPROVED).build();
        when(bingeRepository.findById(9L)).thenReturn(Optional.of(paused));

        assertThatThrownBy(() -> bingeService.getBingeById(9L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getCustomerAboutExperience_hidesNonPublishedVenue_with404() {
        Binge rejected = Binge.builder().id(10L).name("Rejected").active(false)
            .status(BingeApprovalStatus.REJECTED).build();
        when(bingeRepository.findById(10L)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> bingeService.getCustomerAboutExperience(10L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getNearbyBinges_rejectsOutOfRangeCoordinates() {
        assertThatThrownBy(() -> bingeService.getNearbyBinges(95.0, 0.0, 50, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bingeRepository);
    }

    @Test
    void updateBinge_rejectsLoneCoordinate() {
        Binge binge = Binge.builder().id(7L).adminId(11L).name("Downtown").active(true)
            .timezone("Asia/Kolkata").build();
        when(bingeRepository.findById(7L)).thenReturn(Optional.of(binge));

        BingeSaveRequest req = new BingeSaveRequest();
        req.setName("Downtown");
        req.setLatitude(12.97); // longitude omitted

        assertThatThrownBy(() -> bingeService.updateBinge(7L, req, 11L, "ADMIN", false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("provided together");
        verify(bingeRepository, never()).save(any(Binge.class));
    }
}