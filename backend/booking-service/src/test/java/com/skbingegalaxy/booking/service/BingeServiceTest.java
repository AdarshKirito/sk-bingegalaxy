package com.skbingegalaxy.booking.service;

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
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}