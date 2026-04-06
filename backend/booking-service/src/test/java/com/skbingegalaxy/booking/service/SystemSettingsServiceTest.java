package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.SystemSettings;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.SystemSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemSettingsServiceTest {

    @Mock
    private SystemSettingsRepository repo;

    @Mock
    private BingeRepository bingeRepository;

    @InjectMocks
    private SystemSettingsService systemSettingsService;

    @Test
    @DisplayName("Global operational date stays on previous day until audit advances it")
    void globalOperationalDateDoesNotAutoAdvanceAfterMidnight() {
        LocalDate storedDate = LocalDate.of(2026, 4, 5);
        LocalDate clientToday = LocalDate.of(2026, 4, 6);
        SystemSettings settings = SystemSettings.builder().id(1L).operationalDate(storedDate).build();

        when(repo.findById(1L)).thenReturn(Optional.of(settings));

        LocalDate result = systemSettingsService.getOperationalDate(clientToday);

        assertThat(result).isEqualTo(storedDate);
        verify(repo, never()).save(settings);
    }

    @Test
    @DisplayName("Binge operational date stays on previous day until audit advances it")
    void bingeOperationalDateDoesNotAutoAdvanceAfterMidnight() {
        Long bingeId = 42L;
        LocalDate storedDate = LocalDate.of(2026, 4, 5);
        LocalDate clientToday = LocalDate.of(2026, 4, 6);
        Binge binge = Binge.builder().id(bingeId).name("SK Galaxy").adminId(9L).operationalDate(storedDate).build();

        when(bingeRepository.findById(bingeId)).thenReturn(Optional.of(binge));

        LocalDate result = systemSettingsService.getOperationalDate(bingeId, clientToday);

        assertThat(result).isEqualTo(storedDate);
        verify(bingeRepository, never()).save(binge);
    }

    @Test
    @DisplayName("Binge operational date is capped down when stored ahead of client date")
    void bingeOperationalDateIsCappedWhenAheadOfToday() {
        Long bingeId = 42L;
        LocalDate storedDate = LocalDate.of(2026, 4, 7);
        LocalDate clientToday = LocalDate.of(2026, 4, 6);
        Binge binge = Binge.builder().id(bingeId).name("SK Galaxy").adminId(9L).operationalDate(storedDate).build();

        when(bingeRepository.findById(bingeId)).thenReturn(Optional.of(binge));

        LocalDate result = systemSettingsService.getOperationalDate(bingeId, clientToday);

        assertThat(result).isEqualTo(clientToday);
        assertThat(binge.getOperationalDate()).isEqualTo(clientToday);
        verify(bingeRepository).save(binge);
    }
}