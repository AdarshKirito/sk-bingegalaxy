package com.skbingegalaxy.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.payment.entity.AdminApprovalRequest;
import com.skbingegalaxy.payment.entity.AdminApprovalRequest.Status;
import com.skbingegalaxy.payment.repository.AdminApprovalRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminApprovalServiceTest {

    @Mock private AdminApprovalRequestRepository repository;
    @Mock private AuditLogService auditLogService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private AdminApprovalService service;

    /**
     * Regression guard for the maker-checker rollback bug: callers create the approval and
     * then throw a 202 BusinessException to short-circuit, which rolls back their transaction.
     * createRequest MUST run in its own (REQUIRES_NEW) transaction so the approval row survives
     * that rollback — otherwise the "approval id: X" returned to the admin points at nothing.
     * A mocked unit test cannot observe transaction rollback, so we pin the propagation here.
     */
    @Test
    void createRequest_isAnnotatedRequiresNew() throws Exception {
        Method m = AdminApprovalService.class.getMethod("createRequest",
            String.class, String.class, String.class, BigDecimal.class, String.class,
            Long.class, Map.class, String.class, Long.class, String.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("createRequest must be @Transactional").isNotNull();
        assertThat(tx.propagation())
            .as("createRequest must commit in its own transaction so it survives the caller's rollback")
            .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void createRequest_persistsPendingRequest() {
        when(repository.save(any(AdminApprovalRequest.class))).thenAnswer(i -> i.getArgument(0));

        AdminApprovalRequest result = service.createRequest(
            "REFUND_RETRY", "REFUND", "42",
            new BigDecimal("7500.00"), "INR", 9L,
            Map.of("refundId", "42"), "maker@test.com", 1L, "above threshold");

        assertThat(result.getStatus()).isEqualTo(Status.PENDING);
        assertThat(result.getActionType()).isEqualTo("REFUND_RETRY");
        assertThat(result.getRequestedBy()).isEqualTo("maker@test.com");

        ArgumentCaptor<AdminApprovalRequest> saved = ArgumentCaptor.forClass(AdminApprovalRequest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getResourceId()).isEqualTo("42");
        assertThat(saved.getValue().getExpiresAt()).isNotNull();
    }
}
