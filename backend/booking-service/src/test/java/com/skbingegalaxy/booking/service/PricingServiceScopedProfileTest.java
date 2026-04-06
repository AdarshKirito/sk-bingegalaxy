package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CustomerPricingDto;
import com.skbingegalaxy.booking.dto.CustomerPricingSaveRequest;
import com.skbingegalaxy.booking.entity.CustomerPricingProfile;
import com.skbingegalaxy.booking.entity.RateCode;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.CustomerPricingProfileRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.RateCodeRepository;
import com.skbingegalaxy.common.context.BingeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingServiceScopedProfileTest {

    @Mock
    private RateCodeRepository rateCodeRepository;

    @Mock
    private CustomerPricingProfileRepository customerPricingProfileRepository;

    @Mock
    private EventTypeRepository eventTypeRepository;

    @Mock
    private AddOnRepository addOnRepository;

    @InjectMocks
    private PricingService pricingService;

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    @Test
    @DisplayName("Binge-scoped reads fall back to legacy global customer pricing")
    void getCustomerPricingFallsBackToGlobalProfile() {
        Long customerId = 2L;
        Long bingeId = 11L;
        BingeContext.setBingeId(bingeId);

        CustomerPricingProfile globalProfile = CustomerPricingProfile.builder()
            .customerId(customerId)
            .rateCode(RateCode.builder().id(7L).name("VIP").build())
            .eventPricings(new ArrayList<>())
            .addonPricings(new ArrayList<>())
            .build();

        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId)).thenReturn(Optional.empty());
        when(customerPricingProfileRepository.findByCustomerIdAndBingeIdIsNull(customerId)).thenReturn(Optional.of(globalProfile));

        CustomerPricingDto dto = pricingService.getCustomerPricing(customerId);

        assertThat(dto.getCustomerId()).isEqualTo(customerId);
        assertThat(dto.getRateCodeId()).isEqualTo(7L);
        assertThat(dto.getRateCodeName()).isEqualTo("VIP");
    }

    @Test
    @DisplayName("Binge-scoped saves create a binge-specific profile")
    void saveCustomerPricingCreatesScopedProfile() {
        Long customerId = 2L;
        Long bingeId = 11L;
        BingeContext.setBingeId(bingeId);

        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId)).thenReturn(Optional.empty());
        when(customerPricingProfileRepository.save(any(CustomerPricingProfile.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(customerPricingProfileRepository.saveAndFlush(any(CustomerPricingProfile.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerPricingSaveRequest request = CustomerPricingSaveRequest.builder()
            .customerId(customerId)
            .rateCodeId(null)
            .eventPricings(new ArrayList<>())
            .addonPricings(new ArrayList<>())
            .build();

        CustomerPricingDto dto = pricingService.saveCustomerPricing(request);

        ArgumentCaptor<CustomerPricingProfile> savedProfiles = ArgumentCaptor.forClass(CustomerPricingProfile.class);
        verify(customerPricingProfileRepository, atLeastOnce()).save(savedProfiles.capture());

        assertThat(savedProfiles.getAllValues().get(0).getBingeId()).isEqualTo(bingeId);
        assertThat(dto.getCustomerId()).isEqualTo(customerId);
    }
}