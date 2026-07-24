package com.skbingegalaxy.payment.controller;

import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.payment.dto.AdminApprovalRequestDto;
import com.skbingegalaxy.payment.entity.AdminApprovalRequest;
import com.skbingegalaxy.payment.service.AdminApprovalService;
import com.skbingegalaxy.payment.service.PaymentBingeScopeService;
import com.skbingegalaxy.payment.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SEC-010 regression fence: the maker-checker approval surface must be
 * tenant-scoped. Every read is limited to a binge the caller OWNS, and every
 * action re-validates ownership of the TARGET ROW's binge — the
 * client-controlled X-Binge-Id header alone is never enough.
 */
@ExtendWith(MockitoExtension.class)
class AdminApprovalControllerScopeTest {

    @Mock private AdminApprovalService approvalService;
    @Mock private PaymentService paymentService;
    @Mock private PaymentBingeScopeService scopeService;

    @InjectMocks private AdminApprovalController controller;

    private AdminApprovalRequestDto rowOfBinge99;

    @BeforeEach
    void setUp() {
        BingeContext.clear();
        rowOfBinge99 = AdminApprovalRequestDto.builder()
            .id(5L)
            .actionType("REFUND_RETRY")
            .resourceType("REFUND")
            .resourceId("42")
            .bingeId(99L)
            .status("PENDING")
            .build();
    }

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    private void denyOwnershipOfBinge99() {
        doThrow(new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN))
            .when(scopeService).requireBingeOwnership(eq(99L), eq(7L), eq("ADMIN"), any());
    }

    @Test
    @DisplayName("get: foreign binge's approval row → 403, row content never returned")
    void get_foreignRow_forbidden() {
        when(approvalService.get(5L)).thenReturn(rowOfBinge99);
        denyOwnershipOfBinge99();

        assertThatThrownBy(() -> controller.get(5L, 7L, "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("do not own");
    }

    @Test
    @DisplayName("approve: row ownership fence runs BEFORE the state transition")
    void approve_foreignRow_forbidden_noTransition() {
        when(approvalService.get(5L)).thenReturn(rowOfBinge99);
        denyOwnershipOfBinge99();

        assertThatThrownBy(() -> controller.approve(5L, null, 7L, "a@x.com", "ADMIN"))
            .isInstanceOf(BusinessException.class);
        verify(approvalService, never()).approve(anyLong(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("execute-refund-retry: foreign row → 403, no money movement")
    void execute_foreignRow_forbidden_noExecution() {
        when(approvalService.get(5L)).thenReturn(rowOfBinge99);
        denyOwnershipOfBinge99();

        assertThatThrownBy(() -> controller.executeRefundRetry(5L, 7L, "a@x.com", "ADMIN"))
            .isInstanceOf(BusinessException.class);
        verify(paymentService, never()).executeApprovedRefundRetry(anyLong(), any());
    }

    @Test
    @DisplayName("list: binge admin's scope comes from requireManagedBinge, never from the raw header")
    void list_scopedThroughManagedBinge() {
        BingeContext.setBingeId(99L); // client-controlled header value
        var managed = new com.skbingegalaxy.payment.dto.BookingBingeDto();
        managed.setId(11L); // but the caller only owns binge 11
        when(scopeService.requireManagedBinge(eq(7L), eq("ADMIN"), any())).thenReturn(managed);
        when(approvalService.list(any(AdminApprovalRequest.Status.class), any(), anyInt(), anyInt(), eq(11L)))
            .thenReturn(org.springframework.data.domain.Page.empty());

        controller.list("PENDING", null, 0, 50, 7L, "ADMIN");

        // The service must be queried with the OWNED binge id — the ownership
        // check decides the scope, not the spoofable X-Binge-Id value.
        verify(approvalService).list(any(AdminApprovalRequest.Status.class), any(), anyInt(), anyInt(), eq(11L));
    }

    @Test
    @DisplayName("SUPER_ADMIN with no binge selected gets the platform-wide view (null scope)")
    void list_superAdminPlatformView() {
        when(approvalService.list(any(AdminApprovalRequest.Status.class), any(), anyInt(), anyInt(), eq((Long) null)))
            .thenReturn(org.springframework.data.domain.Page.empty());

        controller.list("PENDING", null, 0, 50, 1L, "SUPER_ADMIN");

        verify(approvalService).list(any(AdminApprovalRequest.Status.class), any(), anyInt(), anyInt(), eq((Long) null));
        verify(scopeService, never()).requireManagedBinge(anyLong(), any(), any());
    }
}
