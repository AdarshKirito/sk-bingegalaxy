package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.PublicBingeDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BingeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.CancellationTierService;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer regression tests for the proximity endpoint. The critical thing
 * to lock down is that the literal {@code /binges/nearby} path resolves to the
 * proximity handler and is NOT swallowed by the {@code /binges/{id}} variable
 * mapping (which would 400 trying to parse "nearby" as a Long).
 */
@WebMvcTest(controllers = BingeController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
    })
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class BingeControllerNearbyTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private BingeService bingeService;
    @MockBean private BookingService bookingService;
    @MockBean private CancellationTierService cancellationTierService;
    @MockBean private AdminBingeScopeService adminBingeScopeService;

    @Test
    void nearby_resolvesToProximityHandler_notPathVariable_andReturnsDistance() throws Exception {
        PublicBingeDto dto = PublicBingeDto.builder()
            .id(1L).name("Indiranagar").latitude(12.97).longitude(77.64).distanceKm(2.3).build();
        when(bingeService.getNearbyBinges(12.97, 77.63, 50, 20)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/bookings/binges/nearby")
                .param("lat", "12.97").param("lng", "77.63"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].distanceKm").value(2.3));

        // Proves the literal path won precedence over /binges/{id}.
        verify(bingeService, never()).getBingeById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void nearby_honoursRadiusAndLimitParams() throws Exception {
        when(bingeService.getNearbyBinges(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/bookings/binges/nearby")
                .param("lat", "12.9").param("lng", "77.6")
                .param("radiusKm", "10").param("limit", "5"))
            .andExpect(status().isOk());

        verify(bingeService).getNearbyBinges(12.9, 77.6, 10.0, 5);
    }

    @Test
    void nearby_missingRequiredCoordinate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/binges/nearby").param("lat", "12.97"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void nearby_outOfRangeCoordinate_surfaces400FromService() throws Exception {
        when(bingeService.getNearbyBinges(anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenThrow(new BusinessException("Latitude must be between -90 and 90", HttpStatus.BAD_REQUEST));

        mockMvc.perform(get("/api/v1/bookings/binges/nearby")
                .param("lat", "95").param("lng", "10"))
            .andExpect(status().isBadRequest());
    }
}
