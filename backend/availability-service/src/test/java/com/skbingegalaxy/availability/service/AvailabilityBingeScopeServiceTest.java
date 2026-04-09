package com.skbingegalaxy.availability.service;

import com.skbingegalaxy.availability.client.BookingBingeClient;
import com.skbingegalaxy.availability.dto.BookingBingeDto;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityBingeScopeServiceTest {

    @Mock
    private BookingBingeClient bookingBingeClient;

    @InjectMocks
    private AvailabilityBingeScopeService availabilityBingeScopeService;

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    @Test
    @DisplayName("Availability actions require a selected binge")
    void requireSelectedBingeRejectsMissingContext() {
        assertThatThrownBy(() -> availabilityBingeScopeService.requireSelectedBinge("checking availability"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Select a binge before checking availability")
            .extracting("status")
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Availability admin actions reject another admin's binge")
    void requireManagedBingeRejectsForeignBinge() {
        BingeContext.setBingeId(11L);
        when(bookingBingeClient.getBinge(11L)).thenReturn(ApiResponse.ok(new BookingBingeDto(11L, 44L, true)));

        assertThatThrownBy(() -> availabilityBingeScopeService.requireManagedBinge(9L, "ADMIN", "managing availability"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Access denied: you do not own this binge")
            .extracting("status")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Availability admin actions allow super admins")
    void requireManagedBingeAllowsSuperAdmin() {
        BingeContext.setBingeId(11L);
        when(bookingBingeClient.getBinge(11L)).thenReturn(ApiResponse.ok(new BookingBingeDto(11L, 44L, true)));

        BookingBingeDto resolved = availabilityBingeScopeService.requireManagedBinge(9L, "SUPER_ADMIN", "managing availability");

        assertThat(resolved.getId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("Availability admin actions reject an unknown binge")
    void requireManagedBingeRejectsUnknownBinge() {
        BingeContext.setBingeId(11L);
        when(bookingBingeClient.getBinge(11L)).thenReturn(ApiResponse.ok(null));

        assertThatThrownBy(() -> availabilityBingeScopeService.requireManagedBinge(9L, "ADMIN", "managing availability"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Binge");
    }
}