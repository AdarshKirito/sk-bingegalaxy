package com.skbingegalaxy.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.payment.entity.AuditLog;
import com.skbingegalaxy.payment.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Append-only audit log for money-moving actions.
 *
 * <p>Writes go in a {@code REQUIRES_NEW} transaction so an audit-write failure
 * never rolls back the business action it's auditing, and the business action
 * can't retroactively wipe the audit row if it fails after the audit was
 * written. One-way, append-only, separate from business state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public static final String ACTION_REFUND_ISSUED   = "REFUND_ISSUED";
    public static final String ACTION_REFUND_AUTO     = "REFUND_AUTO_LATE_CAPTURE";
    public static final String ACTION_PAYMENT_CANCEL  = "PAYMENT_CANCELLED";
    public static final String ACTION_CASH_RECORDED   = "CASH_RECORDED";
    public static final String ACTION_PAYMENT_ADDED   = "PAYMENT_ADDED";
    public static final String ACTION_SIM_SUCCESS     = "PAYMENT_SIMULATED";

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor,
                       String action,
                       String resourceType,
                       String resourceId,
                       BigDecimal amount,
                       String currency,
                       Long bingeId,
                       Map<String, Object> metadata) {
        try {
            AuditLog row = AuditLog.builder()
                .actor(actor == null ? "SYSTEM" : actor)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .amount(amount)
                .currency(currency)
                .bingeId(bingeId)
                .metadata(metadata == null ? null : serialize(metadata))
                .build();
            repository.save(row);
        } catch (Exception e) {
            // Never let audit failures cascade — best effort, but shout so we notice.
            log.error("Failed to persist audit log row (action={}, resource={}:{}): {}",
                action, resourceType, resourceId, e.getMessage(), e);
        }
    }

    private String serialize(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return metadata.toString();
        }
    }
}
