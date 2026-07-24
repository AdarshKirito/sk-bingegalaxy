package com.skbingegalaxy.payment.listener;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.UserAnonymizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Right-to-erasure fan-out (DATA-004): redacts the customer PII snapshotted
 * onto payment rows when auth-service anonymizes the user. Payment rows are
 * tombstoned, never deleted — the financial record (amounts, gateway ids,
 * statuses) must survive for GST/audit, but carries no personal data after
 * this runs. Idempotent on re-delivery; failures land in the shared DLT.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserAnonymizedEventListener {

    private final JdbcTemplate jdbcTemplate;

    @KafkaListener(topics = KafkaTopics.USER_ANONYMIZED, groupId = "payment-group")
    @Transactional
    public void onUserAnonymized(UserAnonymizedEvent event) {
        if (event == null || event.getUserId() == null) {
            log.warn("user.anonymized event without userId — skipping");
            return;
        }
        Long uid = event.getUserId();
        String anonEmail = event.getAnonymizedEmail() != null
            ? event.getAnonymizedEmail() : "deleted-" + uid + "@anonymized.invalid";

        int payments = jdbcTemplate.update(
            "UPDATE payments SET customer_name = ?, customer_email = ?, "
            + "customer_phone = NULL, customer_phone_country_code = NULL WHERE customer_id = ?",
            "Deleted User", anonEmail, uid);

        log.info("PII redaction for anonymized user {}: payments={}", uid, payments);
    }
}
