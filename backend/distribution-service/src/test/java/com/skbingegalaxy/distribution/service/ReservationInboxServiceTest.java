package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Reservation inbox (slice 5)")
class ReservationInboxServiceTest {

    @Mock private ReservationInboxRepository inboxRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionDestinationRepository connectionDestinationRepository;
    @Mock private DestinationRepository destinationRepository;
    @Mock private SettlementService settlementService;
    /**
     * receive() writes through this collaborator so the insert and the duplicate
     * recovery each get their OWN transaction. A unique-index violation poisons the
     * Hibernate session it happened on, so a recovery lookup sharing that session is
     * what turned every redelivered message into an HTTP 500.
     */
    @Mock private InboxWriter inboxWriter;

    @InjectMocks private ReservationInboxService service;

    private void givenUsableChannel(boolean deliversReservations) {
        when(connectionRepository.findById(7L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L)
                .status(Connection.ConnectionStatus.ACTIVE).build()));
        when(destinationRepository.findById("VIATOR")).thenReturn(Optional.of(
            Destination.builder().code("VIATOR").displayName("Viator")
                .operatedByProviderCode("VIATOR")
                .deliversReservations(deliversReservations).build()));
        if (deliversReservations) {
            when(connectionDestinationRepository
                .findByConnectionIdAndDestinationCode(7L, "VIATOR"))
                .thenReturn(Optional.of(ConnectionDestination.builder()
                    .id(3L).connectionId(7L).destinationCode("VIATOR").build()));
        }
    }

    private ReservationInboxEntry receive(Long sequence) {
        return service.receive(7L, "VIATOR", "EXT-1",
            ReservationInboxEntry.MessageType.MODIFY, sequence, null, "{}");
    }

    @Test
    @DisplayName("a message is persisted BEFORE anything is interpreted")
    void persistsFirst() {
        givenUsableChannel(true);
        when(inboxRepository.findHighestAppliedSequence(7L, "EXT-1")).thenReturn(Optional.empty());
        when(inboxWriter.insert(any())).thenAnswer(i -> i.getArgument(0));

        ReservationInboxEntry entry = receive(1L);

        // Persist-first is what turns a refused reservation into an explainable row
        // rather than a venue asking why a booking never arrived.
        assertThat(entry.getStatus()).isEqualTo(ReservationInboxEntry.Status.RECEIVED);
        assertThat(entry.getPayloadJson()).isEqualTo("{}");
        verify(inboxWriter).insert(any());
    }

    @Test
    @DisplayName("an out-of-order message is stored as SUPERSEDED, not discarded")
    void outOfOrderIsKept() {
        givenUsableChannel(true);
        // A cancel at sequence 7 was already applied; the modify it superseded arrives
        // late. Applying it would resurrect a cancelled booking.
        when(inboxRepository.findHighestAppliedSequence(7L, "EXT-1")).thenReturn(Optional.of(7L));
        when(inboxWriter.insert(any())).thenAnswer(i -> i.getArgument(0));

        ReservationInboxEntry entry = receive(6L);

        assertThat(entry.getStatus()).isEqualTo(ReservationInboxEntry.Status.SUPERSEDED);
        assertThat(entry.getRejectReason()).contains("does not exceed");
        // Kept, so an operator can answer "why did my modification not take effect".
        verify(inboxWriter).insert(any());
    }

    @Test
    @DisplayName("a feed-only destination cannot deliver reservations")
    void feedOnlyDestinationRefused() {
        givenUsableChannel(false);

        // Google is a feed plus a deep link. A message claiming otherwise is a
        // misconfiguration or a forgery, not a reservation we missed.
        assertThatThrownBy(() -> receive(1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not deliver reservations");
        verify(inboxWriter, never()).insert(any());
    }

    @Test
    @DisplayName("a revoked connection is refused; a PAUSED one is not")
    void revokedRefusedPausedAccepted() {
        when(connectionRepository.findById(7L)).thenReturn(Optional.of(
            Connection.builder().id(7L).status(Connection.ConnectionStatus.REVOKED).build()));

        assertThatThrownBy(() -> receive(1L))
            .isInstanceOf(BusinessException.class).hasMessageContaining("revoked");

        // Pausing stops NEW sales. A message about a reservation already taken still has
        // to be honoured, or travellers are stranded by an operator's pause button.
        reset(connectionRepository);
        givenUsableChannel(true);
        when(connectionRepository.findById(7L)).thenReturn(Optional.of(
            Connection.builder().id(7L).status(Connection.ConnectionStatus.PAUSED).build()));
        when(inboxRepository.findHighestAppliedSequence(any(), any())).thenReturn(Optional.empty());
        when(inboxWriter.insert(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(receive(1L).getStatus()).isEqualTo(ReservationInboxEntry.Status.RECEIVED);
    }

    @Test
    @DisplayName("an oversized payload is refused before it is stored")
    void oversizedPayloadRefused() {
        givenUsableChannel(true);

        // The inbox keeps every payload verbatim for audit, so it needs a bound or one
        // misbehaving caller fills the disk.
        assertThatThrownBy(() -> service.receive(7L, "VIATOR", "EXT-1",
                ReservationInboxEntry.MessageType.CREATE, 1L, null,
                "x".repeat(ReservationInboxService.MAX_PAYLOAD_BYTES + 1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("exceeds");
        verify(inboxWriter, never()).insert(any());
    }

    private static ReservationInboxEntry existingModify() {
        return ReservationInboxEntry.builder().id(11L)
            .connectionId(7L).externalRef("EXT-1")
            .messageType(ReservationInboxEntry.MessageType.MODIFY)
            .status(ReservationInboxEntry.Status.RECEIVED).build();
    }

    @Test
    @DisplayName("a redelivery is recognised without reaching the unique index")
    void redeliveryTakesTheFastPath() {
        givenUsableChannel(true);
        ReservationInboxEntry original = existingModify();
        when(inboxWriter.findCollision(7L, "EXT-1",
            ReservationInboxEntry.MessageType.MODIFY, 1L)).thenReturn(Optional.of(original));

        assertThat(receive(1L)).isSameAs(original);
        // The violation was always handled, but Hibernate logs every one at ERROR with a
        // SQLState — so a provider's normal retry behaviour filled the log with what
        // looked like a database incident.
        verify(inboxWriter, never()).insert(any());
    }

    @Test
    @DisplayName("a redelivery that loses the race still returns the original, not a 500")
    void redeliveryLosingTheRaceIsStillIdempotent() {
        givenUsableChannel(true);
        when(inboxRepository.findHighestAppliedSequence(7L, "EXT-1")).thenReturn(Optional.empty());
        ReservationInboxEntry original = existingModify();

        // Two deliveries in flight at once: the pre-check found nothing, the index did.
        when(inboxWriter.findCollision(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(inboxWriter.insert(any())).thenThrow(
            new org.springframework.dao.DataIntegrityViolationException("uk_inbox_message"));
        when(inboxWriter.findExisting(7L, "EXT-1", ReservationInboxEntry.MessageType.MODIFY))
            .thenReturn(Optional.of(original));

        // Verified end to end before this test existed, and it answered HTTP 500: the
        // recovery lookup ran on the session the violation had already poisoned, so the
        // path built to make redelivery harmless was the one that broke.
        assertThat(receive(1L)).isSameAs(original);
    }

    @Test
    @DisplayName("a later message with a HIGHER sequence is not mistaken for a redelivery")
    void higherSequenceIsANewMessage() {
        givenUsableChannel(true);
        when(inboxRepository.findHighestAppliedSequence(7L, "EXT-1")).thenReturn(Optional.empty());
        // The collision lookup keys on the sequence too. Matching without it would treat
        // a genuine later modification as a duplicate and silently drop it — far worse
        // than an occasional constraint violation.
        when(inboxWriter.findCollision(7L, "EXT-1",
            ReservationInboxEntry.MessageType.MODIFY, 9L)).thenReturn(Optional.empty());
        when(inboxWriter.insert(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(receive(9L).getStatus()).isEqualTo(ReservationInboxEntry.Status.RECEIVED);
        verify(inboxWriter).insert(any());
    }

    @Test
    @DisplayName("a duplicate with nothing to recover still surfaces the violation")
    void unrecoverableDuplicateStillThrows() {
        givenUsableChannel(true);
        when(inboxRepository.findHighestAppliedSequence(7L, "EXT-1")).thenReturn(Optional.empty());
        when(inboxWriter.insert(any())).thenThrow(
            new org.springframework.dao.DataIntegrityViolationException("some other constraint"));
        when(inboxWriter.findExisting(any(), any(), any())).thenReturn(Optional.empty());

        // A violation that is NOT a redelivery must not be swallowed into a silent
        // success — that would lose the message the inbox exists to keep.
        assertThatThrownBy(() -> receive(1L))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("REJECTED and FAILED mean different things")
    void rejectedIsNotFailed() {
        ReservationInboxEntry entry = ReservationInboxEntry.builder().id(4L)
            .status(ReservationInboxEntry.Status.RECEIVED).build();
        when(inboxRepository.findById(4L)).thenReturn(Optional.of(entry));
        when(inboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Legitimately refused (the slot was taken) — NOT retryable. Conflating this
        // with FAILED would retry a rejection forever.
        ReservationInboxEntry rejected = service.markRejected(4L, "slot taken 40s earlier");

        assertThat(rejected.getStatus()).isEqualTo(ReservationInboxEntry.Status.REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("slot taken 40s earlier");
        assertThat(rejected.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("applying records the canonical booking reference and nothing more")
    void markAppliedStoresOnlyTheRef() {
        ReservationInboxEntry entry = ReservationInboxEntry.builder().id(4L)
            .status(ReservationInboxEntry.Status.RECEIVED).build();
        when(inboxRepository.findById(4L)).thenReturn(Optional.of(entry));
        when(inboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // This context stores a reference, never booking detail. A second booking truth
        // is the failure mode the whole distribution design avoids.
        ReservationInboxEntry applied = service.markApplied(4L, "SKBG26ABC", 1L, "INR", 300000L);

        assertThat(applied.getStatus()).isEqualTo(ReservationInboxEntry.Status.APPLIED);
        assertThat(applied.getBookingRef()).isEqualTo("SKBG26ABC");
    }

    @Test
    @DisplayName("a venue with no connections gets an empty inbox without a query")
    void noConnectionsShortCircuits() {
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(java.util.List.of());

        assertThat(service.listForBinge(1L, 50)).isEmpty();
        // Inbox rows key on connection_id, so the tenancy boundary is DERIVED from the
        // venue's own connections. No connections means nothing to scope to.
        verifyNoInteractions(inboxRepository);
    }

    @Test
    @DisplayName("only a FAILED message can be retried")
    void onlyFailedIsRetryable() {
        when(inboxRepository.findById(4L)).thenReturn(Optional.of(
            ReservationInboxEntry.builder().id(4L).connectionId(7L)
                .status(ReservationInboxEntry.Status.REJECTED).build()));
        when(connectionRepository.findByIdAndBingeId(7L, 1L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L).build()));

        // Retrying a REJECTED message would either fail identically or succeed later
        // against a slot someone else has since booked. Retrying a SUPERSEDED one is the
        // cancel-resurrection bug performed by hand.
        assertThatThrownBy(() -> service.retry(1L, 4L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only a FAILED");
        verify(inboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("a FAILED message returns to RECEIVED and clears its error")
    void failedRequeues() {
        when(inboxRepository.findById(4L)).thenReturn(Optional.of(
            ReservationInboxEntry.builder().id(4L).connectionId(7L)
                .status(ReservationInboxEntry.Status.FAILED).rejectReason("timeout").build()));
        when(connectionRepository.findByIdAndBingeId(7L, 1L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L).build()));
        when(inboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ReservationInboxEntry requeued = service.retry(1L, 4L);

        assertThat(requeued.getStatus()).isEqualTo(ReservationInboxEntry.Status.RECEIVED);
        assertThat(requeued.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("another venue's inbox entry is not found")
    void cannotRetryAnotherVenuesEntry() {
        when(inboxRepository.findById(4L)).thenReturn(Optional.of(
            ReservationInboxEntry.builder().id(4L).connectionId(7L)
                .status(ReservationInboxEntry.Status.FAILED).build()));
        when(connectionRepository.findByIdAndBingeId(7L, 999L)).thenReturn(Optional.empty());

        // Scoped through the owning connection, so another venue's entry is NOT FOUND
        // rather than forbidden-but-confirmed-to-exist.
        assertThatThrownBy(() -> service.retry(999L, 4L))
            .isInstanceOf(com.skbingegalaxy.common.exception.ResourceNotFoundException.class);
        verify(inboxRepository, never()).save(any());
    }
}
