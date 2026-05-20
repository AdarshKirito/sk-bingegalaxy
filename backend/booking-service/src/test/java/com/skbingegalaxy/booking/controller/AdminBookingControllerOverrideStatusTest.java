package com.skbingegalaxy.booking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.BookingDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingEventLogService;
import com.skbingegalaxy.booking.service.BookingProjectionService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.IdempotencyService;
import com.skbingegalaxy.booking.service.PricingService;
import com.skbingegalaxy.booking.service.SagaOrchestrator;
import com.skbingegalaxy.booking.service.SystemSettingsService;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for the SUPER_ADMIN status-override endpoint.
 * <p>
 * Mirrors {@link BookingControllerAuthzTest}'s pattern: the security filters
 * are stripped (the gateway is the first line of defence; this test verifies
 * the controller's own authorization check stands alone) and the
 * {@link IdempotencyService} is wired as a pass-through so the
 * {@link BookingService} call surfaces normally.
 */
@WebMvcTest(controllers = AdminBookingController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
    })
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminBookingControllerOverrideStatusTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AdminBingeScopeService adminBingeScopeService;
    @MockBean private BookingService bookingService;
    @MockBean private SystemSettingsService systemSettingsService;
    @MockBean private BookingEventLogService eventLogService;
    @MockBean private BookingProjectionService projectionService;
    @MockBean private SagaOrchestrator sagaOrchestrator;
    @MockBean private PricingService pricingService;
    @MockBean private IdempotencyService idempotencyService;

    private static final String REF = "SKBG25000042";
    private static final String URL = "/api/v1/bookings/admin/" + REF + "/override-status";

    @BeforeEach
    void setUp() {
        // Pass-through wrapper so the underlying service call still happens
        // (and exceptions still surface to GlobalExceptionHandler).
        when(idempotencyService.execute(any(), anyString(), anyString(), any(), any(), any(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(6)).get());
    }

    // ── Authorization ─────────────────────────────────────

    @Test
    void overrideStatus_admin_returns403() throws Exception {
        // Plain ADMIN cannot override — only SUPER_ADMIN.
        mockMvc.perform(post(URL)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "500")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("targetStatus", "CONFIRMED", "reason", "ops review"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                "Only super admins can override booking status"));

        verify(bookingService, never()).adminOverrideStatus(anyString(), any(), any(), anyString());
    }

    @Test
    void overrideStatus_customer_returns403() throws Exception {
        mockMvc.perform(post(URL)
                .header("X-User-Role", "CUSTOMER")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("targetStatus", "CONFIRMED", "reason", "x"))))
            .andExpect(status().isForbidden());

        verify(bookingService, never()).adminOverrideStatus(anyString(), any(), any(), anyString());
    }

    // ── Validation ────────────────────────────────────────

    @Test
    void overrideStatus_missingTargetStatus_returns400() throws Exception {
        mockMvc.perform(post(URL)
                .header("X-User-Role", "SUPER_ADMIN")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "x"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("targetStatus is required"));

        verify(bookingService, never()).adminOverrideStatus(anyString(), any(), any(), anyString());
    }

    @Test
    void overrideStatus_blankReason_returns400() throws Exception {
        mockMvc.perform(post(URL)
                .header("X-User-Role", "SUPER_ADMIN")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("targetStatus", "CONFIRMED", "reason", "   "))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("reason is required for status override"));

        verify(bookingService, never()).adminOverrideStatus(anyString(), any(), any(), anyString());
    }

    @Test
    void overrideStatus_invalidTargetStatus_returns400() throws Exception {
        mockMvc.perform(post(URL)
                .header("X-User-Role", "SUPER_ADMIN")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("targetStatus", "TIME_TRAVELED", "reason", "ops review"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.startsWith("Invalid target status")));

        verify(bookingService, never()).adminOverrideStatus(anyString(), any(), any(), anyString());
    }

    // ── Happy path ────────────────────────────────────────

    @Test
    void overrideStatus_superAdmin_callsServiceWithTrimmedReason() throws Exception {
        BookingDto dto = BookingDto.builder().bookingRef(REF).status(BookingStatus.CONFIRMED).build();
        when(bookingService.adminOverrideStatus(eq(REF), eq(BookingStatus.CONFIRMED),
                eq(7L), eq("Wrongly cancelled — manager OK"))).thenReturn(dto);

        mockMvc.perform(post(URL)
                .header("X-User-Role", "SUPER_ADMIN")
                .header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "targetStatus", "confirmed",   // case-insensitive
                    "reason", "  Wrongly cancelled — manager OK  "))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bookingRef").value(REF))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("Status overridden to CONFIRMED")));

        verify(bookingService).adminOverrideStatus(REF, BookingStatus.CONFIRMED,
            7L, "Wrongly cancelled — manager OK");
    }
}
