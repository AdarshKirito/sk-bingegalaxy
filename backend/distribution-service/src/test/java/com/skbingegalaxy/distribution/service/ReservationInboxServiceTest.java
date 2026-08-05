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
        when(inboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ReservationInboxEntry entry = receive(1L);

        // Persist-first is what turns a refused reservation into an explainable row
        // rather than a venue asking why a booking never arrived.
        assertThat(entry.getStatus()).isEqualTo(ReservationInboxEntry.Status.RECEIVED);
        assertThat(entry.getPayloadJson()).isEqualTo("{}");
        verify(inboxRepository).save(any());
    }

    @Test
    @DisplayName("an out-of-order message is stored as SUPERSEDED, not discarded")
    void outOfOrderIsKept() {
        givenUsableChannel(true);
        // A cancel at sequence 7 was already applied; the modify it superseded arrives
        // late. Applying it would resurrect a cancelled booking.
        when(inboxRepository.findHighestAppliedSequence(7L, "EXT-1")).thenReturn(Optional.of(7L));
        when(inboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ReservationInboxEntry entry = receive(6L);

        assertThat(entry.getStatus()).isEqualTo(ReservationInboxEntry.Status.SUPERSEDED);
        assertThat(entry.getRejectReason()).contains("does not exceed");
        // Kept, so an operator can answer "why did my modification not take effect".
        verify(inboxRepository).save(any());
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
        verify(inboxRepository, never()).save(any());
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
        when(inboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

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
        verify(inboxRepository, never()).save(any());
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
        ReservationInboxEntry applied = service.markApplied(4L, "SKBG26ABC");

        assertThat(applied.getStatus()).isEqualTo(ReservationInboxEntry.Status.APPLIED);
        assertThat(applied.getBookingRef()).isEqualTo("SKBG26ABC");
    }
}
