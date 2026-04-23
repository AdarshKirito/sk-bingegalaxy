package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BookingDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.IdempotencyService;
import com.skbingegalaxy.booking.service.PricingService;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization / IDOR regression tests for customer-facing booking endpoints.
 * <p>
 * Security filters are disabled ({@code addFilters = false}) so these tests exercise
 * the controller's own ownership checks in isolation — which is the authoritative
 * second line of defence. The gateway ({@link com.skbingegalaxy.gateway.filter.JwtAuthenticationFilter})
 * is the first line but controller-level checks must stand alone.
 */
@WebMvcTest(controllers = BookingController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
    })
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class BookingControllerAuthzTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private BookingService bookingService;
    @MockBean private PricingService pricingService;
    @MockBean private AdminBingeScopeService adminBingeScopeService;
    @MockBean private IdempotencyService idempotencyService;

    private BookingDto bookingOwnedBy42;

    @BeforeEach
    void setUp() {
        // BingeContextFilter is a servlet filter and doesn't run under addFilters=false,
        // so we populate the ThreadLocal that @ModelAttribute validateSelectedBinge reads.
        BingeContext.setBingeId(1L);
        bookingOwnedBy42 = BookingDto.builder()
            .bookingRef("SKBG25000042")
            .customerId(42L)
            .customerName("Alice")
            .customerEmail("alice@example.com")
            .build();
    }

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    // ── GET /{bookingRef} ────────────────────────────────

    @Test
    void getBooking_owner_returns200() throws Exception {
        when(bookingService.getByRef("SKBG25000042")).thenReturn(bookingOwnedBy42);

        mockMvc.perform(get("/api/v1/bookings/SKBG25000042")
                .header("X-User-Id", "42")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bookingRef").value("SKBG25000042"));
    }

    @Test
    void getBooking_differentCustomer_returns403() throws Exception {
        // Classic IDOR: customer 99 tries to read customer 42's booking.
        when(bookingService.getByRef("SKBG25000042")).thenReturn(bookingOwnedBy42);

        mockMvc.perform(get("/api/v1/bookings/SKBG25000042")
                .header("X-User-Id", "99")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not authorized to view this booking"));
    }

    @Test
    void getBooking_admin_bypassesOwnershipCheck() throws Exception {
        when(bookingService.getByRef("SKBG25000042")).thenReturn(bookingOwnedBy42);

        mockMvc.perform(get("/api/v1/bookings/SKBG25000042")
                .header("X-User-Id", "500")
                .header("X-User-Role", "ADMIN")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isOk());

        verify(adminBingeScopeService).requireManagedBinge(anyLong(), anyString(), anyString());
    }

    @Test
    void getBooking_roleHeaderMissing_returns401() throws Exception {
        // Missing X-User-Role → GlobalExceptionHandler maps to 401 Unauthorized.
        // Guards against accidental header removal upstream leading to an auth bypass.
        mockMvc.perform(get("/api/v1/bookings/SKBG25000042")
                .header("X-User-Id", "42")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isUnauthorized());
    }

    // ── POST /{bookingRef}/cancel ────────────────────────

    @Test
    void cancelBooking_nonOwner_serviceRejects() throws Exception {
        // Service-layer ownership check: BusinessException propagates to 400 by GlobalExceptionHandler.
        when(bookingService.cancelBookingByCustomer("SKBG25000042", 99L))
            .thenThrow(new BusinessException("Not authorised to cancel this booking"));

        mockMvc.perform(post("/api/v1/bookings/SKBG25000042/cancel")
                .header("X-User-Id", "99")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Not authorised to cancel this booking"));
    }

    // ── GET /my — customer can only see their own ───────

    @Test
    void getMyBookings_usesCallerUserId_notBodyParam() throws Exception {
        when(bookingService.getCustomerBookings(42L)).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/v1/bookings/my")
                .header("X-User-Id", "42")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1")
                // Would-be parameter-tampering attempt.
                .param("userId", "7"))
            .andExpect(status().isOk());

        // Service must be called with the header-bound user id, not any query param.
        verify(bookingService).getCustomerBookings(42L);
        verify(bookingService, never()).getCustomerBookings(7L);
    }
}
