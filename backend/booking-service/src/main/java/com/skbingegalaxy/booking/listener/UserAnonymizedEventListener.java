package com.skbingegalaxy.booking.listener;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.UserAnonymizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Right-to-erasure fan-out (DATA-004): when auth-service anonymizes a user,
 * redact every PII snapshot this service holds for them. Rows are tombstoned,
 * not deleted — booking/financial records must survive for GST/audit purposes,
 * but with no personal data left in them.
 *
 * <p>Idempotent: re-delivery re-applies the same UPDATE. Failures route to the
 * shared DLT via the common error handler, so a redaction is never silently
 * dropped.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserAnonymizedEventListener {

    private static final String REDACTED_NAME = "Deleted User";

    private final JdbcTemplate jdbcTemplate;

    @KafkaListener(topics = KafkaTopics.USER_ANONYMIZED, groupId = "booking-group")
    @Transactional
    public void onUserAnonymized(UserAnonymizedEvent event) {
        if (event == null || event.getUserId() == null) {
            log.warn("user.anonymized event without userId — skipping");
            return;
        }
        Long uid = event.getUserId();
        String anonEmail = event.getAnonymizedEmail() != null
            ? event.getAnonymizedEmail() : "deleted-" + uid + "@anonymized.invalid";

        int bookings = jdbcTemplate.update(
            "UPDATE bookings SET customer_name = ?, customer_email = ?, "
            + "customer_phone = NULL, customer_phone_country_code = NULL WHERE customer_id = ?",
            REDACTED_NAME, anonEmail, uid);
        int waitlist = jdbcTemplate.update(
            "UPDATE waitlist_entries SET customer_name = ?, customer_email = ?, "
            + "customer_phone = NULL, customer_phone_country_code = NULL WHERE customer_id = ?",
            REDACTED_NAME, anonEmail, uid);
        int holds = jdbcTemplate.update(
            "UPDATE slot_holds SET customer_name = ?, customer_email = ? WHERE customer_id = ?",
            REDACTED_NAME, anonEmail, uid);
        int transfersFrom = jdbcTemplate.update(
            "UPDATE booking_transfers SET from_customer_name = ?, from_customer_email = ? "
            + "WHERE from_customer_id = ?",
            REDACTED_NAME, anonEmail, uid);
        // Transfer recipients may predate their account (invited by email), so
        // match by the pre-anonymization email as well as the linked id.
        int transfersTo = jdbcTemplate.update(
            "UPDATE booking_transfers SET to_name = ?, to_email = ?, "
            + "to_phone = NULL, to_phone_country_code = NULL "
            + "WHERE to_customer_id = ? OR to_email = ?",
            REDACTED_NAME, anonEmail, uid,
            event.getOriginalEmail() != null ? event.getOriginalEmail() : anonEmail);

        log.info("PII redaction for anonymized user {}: bookings={} waitlist={} slotHolds={} "
            + "transfersFrom={} transfersTo={}", uid, bookings, waitlist, holds, transfersFrom, transfersTo);
    }
}
