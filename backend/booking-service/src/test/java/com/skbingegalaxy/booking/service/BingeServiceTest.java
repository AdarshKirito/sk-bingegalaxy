package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.CustomerDashboardExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardSlideDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CustomerPricingProfileRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.RateCodeRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
}