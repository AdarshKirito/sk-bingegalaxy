package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CustomerPricingDto;
import com.skbingegalaxy.booking.dto.CustomerPricingSaveRequest;
import com.skbingegalaxy.booking.dto.RateCodeSaveRequest;
import com.skbingegalaxy.booking.entity.CustomerPricingProfile;
import com.skbingegalaxy.booking.entity.RateCode;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.CustomerPricingProfileRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.RateCodeRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("Binge-scoped reads do not leak a legacy global customer pricing profile")
    void getCustomerPricingDoesNotFallBackToGlobalProfile() {
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
        CustomerPricingDto dto = pricingService.getCustomerPricing(customerId);

        assertThat(dto.getCustomerId()).isEqualTo(customerId);
        assertThat(dto.getRateCodeId()).isNull();
        assertThat(dto.getRateCodeName()).isNull();
        assertThat(dto.isScopedProfile()).isFalse();
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

    @Test
    @DisplayName("Binge-scoped rate code creation stamps the selected binge")
    void createRateCodeUsesSelectedBingeId() {
        Long bingeId = 11L;
        BingeContext.setBingeId(bingeId);

        when(rateCodeRepository.existsByNameAndBingeId("VIP", bingeId)).thenReturn(false);
        when(rateCodeRepository.save(any(RateCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        pricingService.createRateCode(RateCodeSaveRequest.builder()
            .name("VIP")
            .description("Scoped rate code")
            .eventPricings(new ArrayList<>())
            .addonPricings(new ArrayList<>())
            .build());

        ArgumentCaptor<RateCode> savedRateCodes = ArgumentCaptor.forClass(RateCode.class);
        verify(rateCodeRepository, atLeastOnce()).save(savedRateCodes.capture());
        assertThat(savedRateCodes.getAllValues().get(0).getBingeId()).isEqualTo(bingeId);
    }

    @Test
    @DisplayName("Binge-scoped rate code reads do not expose another binge's rate code")
    void getRateCodeByIdRejectsDifferentBinge() {
        Long bingeId = 11L;
        BingeContext.setBingeId(bingeId);

        when(rateCodeRepository.findByIdAndBingeId(7L, bingeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pricingService.getRateCodeById(7L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Deleting a rate code requires it to be inactive")
    void deleteRateCodeRequiresInactiveStatus() {
        Long bingeId = 11L;
        BingeContext.setBingeId(bingeId);

        RateCode rateCode = RateCode.builder().id(7L).name("VIP").active(true).build();
        when(rateCodeRepository.findByIdAndBingeId(7L, bingeId)).thenReturn(Optional.of(rateCode));

        assertThatThrownBy(() -> pricingService.deleteRateCode(7L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Deactivate the rate code");
    }

    @Test
    @DisplayName("Deleting customer pricing removes the scoped profile")
    void deleteCustomerPricingDeletesScopedProfile() {
        Long customerId = 2L;
        Long bingeId = 11L;
        CustomerPricingProfile profile = CustomerPricingProfile.builder()
            .id(5L)
            .customerId(customerId)
            .bingeId(bingeId)
            .build();

        BingeContext.setBingeId(bingeId);
        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId)).thenReturn(Optional.of(profile));

        pricingService.deleteCustomerPricing(customerId);

        verify(customerPricingProfileRepository).delete(profile);
    }
}