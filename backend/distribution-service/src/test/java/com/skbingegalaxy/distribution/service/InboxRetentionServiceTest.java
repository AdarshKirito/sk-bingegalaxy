package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import com.skbingegalaxy.distribution.repository.ReservationInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Retention for the traveller details the inbox now stores.
 *
 * <p>The inbox keeps every provider message verbatim, which was harmless while a message
 * carried only identifiers. It stopped being harmless when OCTO reservations began
 * carrying {@code contact} — a name, an email and a phone number for someone who has no
 * SK account and never agreed to anything with SK Binge. That made an audit table into a
 * second, unmanaged store of third-party personal data, outside the
 * {@code user.anonymized} fan-out that covers every other service (a channel guest has no
 * account, so no erasure event will ever name them).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Inbox payload retention")
class InboxRetentionServiceTest {

    @Mock private ReservationInboxRepository inboxRepository;
    @InjectMocks private InboxRetentionService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "retentionDays", 30);
    }

    @SuppressWarnings("unchecked")
    private void givenStale(ReservationInboxEntry... entries) {
        Page<ReservationInboxEntry> page = new PageImpl<>(List.of(entries));
        when(inboxRepository.findByStatusInAndReceivedAtBeforeAndPayloadJsonNot(
            anyList(), any(LocalDateTime.class), anyString(), any())).thenReturn(page);
    }

    private static ReservationInboxEntry withPayload(ReservationInboxEntry.Status status) {
        return ReservationInboxEntry.builder().id(1L).status(status)
            .payloadJson("{\"contact\":{\"fullName\":\"Asha Rao\","
                       + "\"emailAddress\":\"asha@example.com\"}}")
            .build();
    }

    @Test
    @DisplayName("replaces the payload but keeps the row that explains what happened")
    void redactsPayloadKeepsRow() {
        ReservationInboxEntry applied = withPayload(ReservationInboxEntry.Status.APPLIED);
        applied.setBookingRef("SKBG26ABC");
        givenStale(applied);

        assertThat(service.redactExpiredPayloads()).isEqualTo(1);

        ArgumentCaptor<List<ReservationInboxEntry>> saved = ArgumentCaptor.forClass(List.class);
        verify(inboxRepository).saveAll(saved.capture());
        ReservationInboxEntry result = saved.getValue().get(0);

        assertThat(result.getPayloadJson()).isEqualTo(InboxRetentionService.REDACTED);
        assertThat(result.getPayloadJson()).doesNotContain("asha@example.com", "Asha Rao");
        // Deleting the row would destroy the evidence that explains a refused or
        // superseded reservation — the thing the inbox exists for.
        assertThat(result.getStatus()).isEqualTo(ReservationInboxEntry.Status.APPLIED);
        assertThat(result.getBookingRef()).isEqualTo("SKBG26ABC");
    }

    @Test
    @DisplayName("only terminal statuses are offered for redaction")
    void onlyTerminalStatuses() {
        givenStale(withPayload(ReservationInboxEntry.Status.APPLIED));

        service.redactExpiredPayloads();

        ArgumentCaptor<List<ReservationInboxEntry.Status>> statuses =
            ArgumentCaptor.forClass(List.class);
        verify(inboxRepository).findByStatusInAndReceivedAtBeforeAndPayloadJsonNot(
            statuses.capture(), any(), any(), any());

        // RECEIVED still has to be applied and the processor reads the payload to do it;
        // FAILED is retryable from the recovery console. Redacting either would strand a
        // real reservation.
        assertThat(statuses.getValue())
            .containsExactlyInAnyOrder(
                ReservationInboxEntry.Status.APPLIED,
                ReservationInboxEntry.Status.REJECTED,
                ReservationInboxEntry.Status.SUPERSEDED)
            .doesNotContain(ReservationInboxEntry.Status.RECEIVED,
                            ReservationInboxEntry.Status.FAILED);
    }

    @Test
    @DisplayName("already-redacted rows are excluded, so the sweep converges")
    void excludesAlreadyRedacted() {
        givenStale(withPayload(ReservationInboxEntry.Status.REJECTED));

        service.redactExpiredPayloads();

        ArgumentCaptor<String> excluded = ArgumentCaptor.forClass(String.class);
        verify(inboxRepository).findByStatusInAndReceivedAtBeforeAndPayloadJsonNot(
            anyList(), any(), excluded.capture(), any());

        // Without this the sweep would re-select and re-save the same rows forever, and
        // the log line would report work that was not being done.
        assertThat(excluded.getValue()).isEqualTo(InboxRetentionService.REDACTED);
    }

    @Test
    @DisplayName("nothing stale means no write at all")
    void noWriteWhenNothingStale() {
        when(inboxRepository.findByStatusInAndReceivedAtBeforeAndPayloadJsonNot(
            anyList(), any(), anyString(), any())).thenReturn(Page.empty());

        assertThat(service.redactExpiredPayloads()).isZero();
        verify(inboxRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("the cutoff honours the configured retention window")
    void cutoffFollowsConfiguration() {
        ReflectionTestUtils.setField(service, "retentionDays", 7);
        givenStale(withPayload(ReservationInboxEntry.Status.APPLIED));

        service.redactExpiredPayloads();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(inboxRepository).findByStatusInAndReceivedAtBeforeAndPayloadJsonNot(
            anyList(), cutoff.capture(), anyString(), any());

        LocalDateTime expected = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(7);
        assertThat(cutoff.getValue())
            .isAfter(expected.minusMinutes(1))
            .isBefore(expected.plusMinutes(1));
    }
}
