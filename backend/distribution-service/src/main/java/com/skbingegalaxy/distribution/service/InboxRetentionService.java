package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import com.skbingegalaxy.distribution.repository.ReservationInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Redacts the traveller's details from inbox messages once they can no longer be acted on.
 *
 * <p><b>Why this exists.</b> The inbox stores every provider message verbatim, which was
 * harmless while a message carried only identifiers. It stopped being harmless the moment
 * OCTO reservations began carrying {@code contact} — a name, an email address and a phone
 * number for a person who has no SK account and never agreed to anything with SK Binge.
 * That turned an audit table into a second, unmanaged store of third-party personal data,
 * sitting outside the {@code user.anonymized} erasure fan-out that covers every other
 * service (a channel guest has no account, so no erasure event will ever name them).
 *
 * <p><b>The row survives; only the personal data leaves.</b> Deleting the row would
 * destroy the evidence that explains a refused or superseded reservation — the thing the
 * inbox exists for. The message type, ordering basis, status, reject reason and booking
 * reference are all retained; the payload is replaced with a marker. What was sold, to
 * whom, and for how much remains in booking-service, which is the system of record for it
 * and has its own retention.
 *
 * <p><b>Only terminal messages.</b> A RECEIVED message still has to be applied, and the
 * processor reads the payload to do it. Redacting one would strand a real reservation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboxRetentionService {

    private final ReservationInboxRepository inboxRepository;

    /** Stored in place of the payload, so a reader can tell redacted from empty. */
    static final String REDACTED = "{\"redacted\":true}";

    /**
     * How long a terminal message keeps its payload.
     *
     * <p>Long enough to investigate a dispute a traveller raises weeks later, short
     * enough that this is not a standing personal-data estate. Matches the 30-day DLT
     * retention the Kafka topics use, for the same reason: that is the forensic window
     * the platform has already settled on.
     */
    @Value("${distribution.inbox.payload-retention-days:30}")
    private int retentionDays;

    /** Bounded so one sweep cannot lock a large table for an unbounded time. */
    private static final int BATCH_SIZE = 500;

    /**
     * Statuses that will never be processed again. FAILED is deliberately absent: it is
     * retryable from the recovery console, and redacting it would turn a recoverable
     * reservation into one that can only fail differently.
     */
    private static final List<ReservationInboxEntry.Status> TERMINAL = List.of(
        ReservationInboxEntry.Status.APPLIED,
        ReservationInboxEntry.Status.REJECTED,
        ReservationInboxEntry.Status.SUPERSEDED);

    @Transactional
    public int redactExpiredPayloads() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays);

        List<ReservationInboxEntry> stale = inboxRepository
            .findByStatusInAndReceivedAtBeforeAndPayloadJsonNot(
                TERMINAL, cutoff, REDACTED,
                org.springframework.data.domain.PageRequest.of(0, BATCH_SIZE))
            .getContent();

        if (stale.isEmpty()) return 0;

        for (ReservationInboxEntry entry : stale) {
            entry.setPayloadJson(REDACTED);
        }
        inboxRepository.saveAll(stale);
        log.info("Inbox retention: redacted the payload of {} message(s) older than {} days",
            stale.size(), retentionDays);
        return stale.size();
    }
}
