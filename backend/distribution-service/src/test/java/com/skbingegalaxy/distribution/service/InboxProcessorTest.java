package com.skbingegalaxy.distribution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.distribution.client.BookingIngestClient;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.ListingMapping;
import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
import com.skbingegalaxy.distribution.repository.ListingMappingRepository;
import com.skbingegalaxy.distribution.repository.ReservationInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The link that turns an accepted reservation into a booking.
 *
 * <p>Without it the OCTO endpoints recorded every reservation faithfully and nothing
 * consumed them — a reseller could reserve, confirm, receive an acknowledgement, and the
 * venue would have no record. An API that accepts and silently drops is worse than one
 * that refuses: the slot gets sold twice and the traveller arrives to nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Inbox to canonical booking (Phase 11)")
class InboxProcessorTest {

    @Mock private ReservationInboxRepository inboxRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private ListingMappingRepository listingRepository;
    @Mock private ReservationInboxService inboxService;
    @Mock private BookingIngestClient bookingIngestClient;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private InboxProcessor processor;

    private static final String PAYLOAD =
        "{\"productId\":\"14\",\"localDate\":\"2026-09-01\",\"startTime\":\"18:00\","
      + "\"durationMinutes\":120,\"guests\":2,\"currency\":\"INR\",\"grossMinor\":300000}";

    private static ReservationInboxEntry entry(ReservationInboxEntry.MessageType type, String payload) {
        return ReservationInboxEntry.builder().id(9L).connectionId(7L)
            .destinationCode("SIMULATOR").externalRef("EXT-1").messageType(type)
            .status(ReservationInboxEntry.Status.RECEIVED).payloadJson(payload).build();
    }

    @BeforeEach
    void connectionAndListingExist() {
        lenient().when(connectionRepository.findById(7L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L).providerCode("SIMULATOR").build()));
        lenient().when(listingRepository.findByBingeId(1L)).thenReturn(List.of(
            ListingMapping.builder().id(1L).bingeId(1L).eventTypeId(14L)
                .publishState(ListingMapping.PublishState.LIVE).build()));
    }

    @Test
    @DisplayName("a CREATE becomes a canonical booking and the entry is APPLIED")
    void createBecomesBooking() {
        when(bookingIngestClient.ingest(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any(), any()))
            .thenReturn(new BookingIngestClient.Result.Accepted("SKBG26X", true));

        assertThat(processor.process(entry(ReservationInboxEntry.MessageType.CREATE, PAYLOAD)))
            .isTrue();

        // markApplied also creates the receivable, from the connection's own terms.
        verify(inboxService).markApplied(9L, "SKBG26X", 1L, "INR", 300000L);
    }

    @Test
    @DisplayName("a business refusal is REJECTED, not retried forever")
    void refusalIsRejected() {
        when(bookingIngestClient.ingest(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any(), any()))
            .thenReturn(new BookingIngestClient.Result.Rejected("slot taken"));

        processor.process(entry(ReservationInboxEntry.MessageType.CREATE, PAYLOAD));

        verify(inboxService).markRejected(9L, "slot taken");
        verify(inboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("a transport error is FAILED, which stays retryable")
    void transportErrorIsRetryable() {
        when(bookingIngestClient.ingest(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any(), any()))
            .thenReturn(new BookingIngestClient.Result.Failed("timeout"));
        when(inboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ReservationInboxEntry e = entry(ReservationInboxEntry.MessageType.CREATE, PAYLOAD);
        processor.process(e);

        // Collapsing FAILED into REJECTED abandons a real reservation over a blip.
        assertThat(e.getStatus()).isEqualTo(ReservationInboxEntry.Status.FAILED);
        verify(inboxService, never()).markRejected(anyLong(), any());
    }

    @Test
    @DisplayName("a CANCEL is never sent down the creation path")
    void cancelIsNotACreation() {
        // Ingesting a cancellation would create a booking FROM a cancellation, the exact
        // inversion the ordering rules exist to prevent.
        processor.process(entry(ReservationInboxEntry.MessageType.CANCEL, PAYLOAD));

        verifyNoInteractions(bookingIngestClient);
        verify(inboxService).markRejected(eq(9L), contains("not yet applied"));
    }

    @Test
    @DisplayName("a product with no LIVE listing is refused")
    void unmappedProductRefused() {
        when(listingRepository.findByBingeId(1L)).thenReturn(List.of(
            ListingMapping.builder().id(1L).bingeId(1L).eventTypeId(14L)
                .publishState(ListingMapping.PublishState.DRAFT).build()));

        // Resolving through the mapping rather than trusting an id in the payload is what
        // stops a reseller booking inventory it was never offered.
        processor.process(entry(ReservationInboxEntry.MessageType.CREATE, PAYLOAD));

        verifyNoInteractions(bookingIngestClient);
        verify(inboxService).markRejected(eq(9L), contains("No live listing"));
    }

    @Test
    @DisplayName("an unparseable payload is REJECTED, because retrying cannot help")
    void unparseablePayloadRejected() {
        processor.process(entry(ReservationInboxEntry.MessageType.CREATE, "not json"));

        verifyNoInteractions(bookingIngestClient);
        verify(inboxService).markRejected(eq(9L), contains("could not be parsed"));
    }
}
