package com.skbingegalaxy.payment.controller;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.GlobalExceptionHandler;
import com.skbingegalaxy.payment.dto.PaymentDto;
import com.skbingegalaxy.payment.service.PaymentBingeScopeService;
import com.skbingegalaxy.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization / IDOR regression tests for customer-facing payment endpoints.
 * <p>
 * Security filters are disabled so these tests exercise the service-level ownership
 * checks (the authoritative second line of defence behind the API gateway).
 */
@WebMvcTest(controllers = PaymentController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
    })
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PaymentControllerAuthzTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private PaymentService paymentService;
    @MockBean private PaymentBingeScopeService scopeService;
    @MockBean private com.skbingegalaxy.payment.service.IdempotencyService idempotencyService;

    private PaymentDto paymentOwnedBy42;

    @BeforeEach
    void setUp() {
        paymentOwnedBy42 = PaymentDto.builder()
            .id(7L)
            .customerId(42L)
            .transactionId("TXN-42")
            .amount(new BigDecimal("500.00"))
            .build();
        // Idempotency wrapper is transparent for tests — delegate to the supplied work.
        org.mockito.Mockito.lenient().when(idempotencyService.execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(6)).get());
    }

    // ── GET /transaction/{transactionId} ──────────────────

    @Test
    void getByTransactionId_owner_returns200() throws Exception {
        when(paymentService.getPaymentByTransactionId("TXN-42", 42L, "CUSTOMER"))
            .thenReturn(paymentOwnedBy42);

        mockMvc.perform(get("/api/v1/payments/transaction/TXN-42")
                .header("X-User-Id", "42")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transactionId").value("TXN-42"));
    }

    @Test
    void getByTransactionId_differentCustomer_returns403() throws Exception {
        // Service-layer ensurePaymentAccess throws BusinessException(FORBIDDEN).
        when(paymentService.getPaymentByTransactionId("TXN-42", 99L, "CUSTOMER"))
            .thenThrow(new BusinessException("Not authorized to view this payment", HttpStatus.FORBIDDEN));

        mockMvc.perform(get("/api/v1/payments/transaction/TXN-42")
                .header("X-User-Id", "99")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not authorized to view this payment"));
    }

    // ── GET /booking/{bookingRef} ─────────────────────────

    @Test
    void getByBookingRef_nonOwner_returns403() throws Exception {
        // Even if booking has payments, if none belong to requester → FORBIDDEN.
        when(paymentService.getPaymentsByBookingRef("SKBG25000042", 99L, "CUSTOMER"))
            .thenThrow(new BusinessException("Not authorized to access payments for this booking",
                HttpStatus.FORBIDDEN));

        mockMvc.perform(get("/api/v1/payments/booking/SKBG25000042")
                .header("X-User-Id", "99")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isForbidden());
    }

    // ── POST /cancel/{transactionId} ──────────────────────

    @Test
    void cancelPayment_nonOwner_serviceRejectsWith403() throws Exception {
        when(paymentService.cancelPayment("TXN-42", 99L))
            .thenThrow(new BusinessException("Not authorized to cancel this payment", HttpStatus.FORBIDDEN));

        mockMvc.perform(post("/api/v1/payments/cancel/TXN-42")
                .header("X-User-Id", "99")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not authorized to cancel this payment"));
    }

    // ── GET /my — paging ─────────────────────────────────

    @Test
    void getMyPayments_usesCallerUserId_notQueryParam() throws Exception {
        when(paymentService.getCustomerPaymentsPaginated(eq(42L), org.mockito.ArgumentMatchers.any()))
            .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/payments/my")
                .header("X-User-Id", "42")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1")
                // Tampering attempt — should be ignored.
                .param("userId", "7"))
            .andExpect(status().isOk());

        verify(paymentService).getCustomerPaymentsPaginated(eq(42L), org.mockito.ArgumentMatchers.any());
        verify(paymentService, never())
            .getCustomerPaymentsPaginated(eq(7L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getMyPayments_sizeCappedAt100() throws Exception {
        // Controller caps size at 100. Verify Pageable passed to service has size ≤ 100.
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);

        when(paymentService.getCustomerPaymentsPaginated(eq(42L), captor.capture()))
            .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/payments/my")
                .header("X-User-Id", "42")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1")
                .param("size", "500"))
            .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    // ── Simulate admin-only endpoint — role check ────────

    @Test
    void simulatePayment_nonAdmin_returns403() throws Exception {
        // Controller checks X-User-Role directly and throws BusinessException(FORBIDDEN).
        mockMvc.perform(post("/api/v1/payments/admin/simulate/TXN-42")
                .header("X-User-Id", "42")
                .header("X-User-Role", "CUSTOMER")
                .header("X-Binge-Id", "1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Only admins can simulate payments"));
    }
}
