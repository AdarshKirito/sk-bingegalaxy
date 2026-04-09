package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBingeScopeServiceTest {

    @Mock
    private BingeRepository bingeRepository;

    @InjectMocks
    private AdminBingeScopeService adminBingeScopeService;

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    @Test
    @DisplayName("Pricing management requires a selected binge")
    void requireManagedBingeRejectsMissingBingeContext() {
        assertThatThrownBy(() -> adminBingeScopeService.requireManagedBinge(9L, "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Select a binge before managing pricing")
            .extracting("status")
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Pricing management rejects a nonexistent selected binge")
    void requireManagedBingeRejectsUnknownBinge() {
        Long bingeId = 11L;
        BingeContext.setBingeId(bingeId);
        when(bingeRepository.findById(bingeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminBingeScopeService.requireManagedBinge(9L, "ADMIN"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Binge");
    }

    @Test
    @DisplayName("Regular admins can manage their own selected binge")
    void requireManagedBingeAllowsOwnedBinge() {
        Long bingeId = 11L;
        Long adminId = 9L;
        Binge binge = Binge.builder().id(bingeId).adminId(adminId).build();
        BingeContext.setBingeId(bingeId);
        when(bingeRepository.findById(bingeId)).thenReturn(Optional.of(binge));

        Binge resolved = adminBingeScopeService.requireManagedBinge(adminId, "ADMIN");

        assertThat(resolved).isSameAs(binge);
    }

    @Test
    @DisplayName("Regular admins cannot manage another admin's binge")
    void requireManagedBingeRejectsForeignBinge() {
        Long bingeId = 11L;
        BingeContext.setBingeId(bingeId);
        when(bingeRepository.findById(bingeId)).thenReturn(Optional.of(Binge.builder().id(bingeId).adminId(44L).build()));

        assertThatThrownBy(() -> adminBingeScopeService.requireManagedBinge(9L, "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Access denied: you do not own this binge")
            .extracting("status")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Super admins can manage any selected binge")
    void requireManagedBingeAllowsSuperAdmin() {
        Long bingeId = 11L;
        Binge binge = Binge.builder().id(bingeId).adminId(44L).build();
        BingeContext.setBingeId(bingeId);
        when(bingeRepository.findById(bingeId)).thenReturn(Optional.of(binge));

        Binge resolved = adminBingeScopeService.requireManagedBinge(9L, "SUPER_ADMIN");

        assertThat(resolved).isSameAs(binge);
    }
}