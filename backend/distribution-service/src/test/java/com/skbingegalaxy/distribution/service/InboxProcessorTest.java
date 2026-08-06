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
import org.junit.jupiter.api.Nested;
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
 *
 * <p><b>The dialect tests are the ones that matter most.</b> A worker that consumes a
 * payload nobody writes fails exactly as invisibly as no worker at all — the inbox fills
 * with polite refusals and every dashboard stays green.
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

    /**
     * What a provider ADAPTER would write: the platform's own vocabulary, flat.
     * Supported so that adding a second, non-OCTO provider does not require this class
     * to learn a third dialect.
     */
    private static final String FLAT_PAYLOAD =
        "{\"productId\":\"14\",\"localDate\":\"2026-09-01\",\"startTime\":\"18:00\","
      + "\"durationMinutes\":120,\"guests\":2,\"guestName\":\"Asha Rao\","
      + "\"currency\":\"INR\",\"grossMinor\":300000}";

    /**
     * What {@code OctoBookingController} ACTUALLY stores: the reseller's own vocabulary.
     * This is the shape that reaches the inbox in production, and the one the processor
     * used to be unable to read.
     */
    private static final String OCTO_PAYLOAD =
        "{\"uuid\":\"EXT-1\",\"productId\":\"14\",\"optionId\":\"default\","
      + "\"availabilityId\":\"2026-09-01T18:00|120\","
      + "\"unitItems\":[{\"unitId\":\"adult\"},{\"unitId\":\"adult\"}],"
      + "\"contact\":{\"fullName\":\"Asha Rao\",\"emailAddress\":\"asha@example.com\"},"
      + "\"pricing\":{\"retail\":300000,\"currency\":\"INR\"}}";

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

    private void ingestReturns(BookingIngestClient.Result result) {
        when(bookingIngestClient.ingest(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any(), any())).thenReturn(result);
    }

    @Test
    @DisplayName("a CREATE becomes a canonical booking and the entry is APPLIED")
    void createBecomesBooking() {
        ingestReturns(new BookingIngestClient.Result.Accepted("SKBG26X", true));

        assertThat(processor.process(entry(ReservationInboxEntry.MessageType.CREATE, FLAT_PAYLOAD)))
            .isTrue();

        // markApplied also creates the receivable, from the connection's own terms.
        verify(inboxService).markApplied(9L, "SKBG26X", 1L, "INR", 300000L);
    }

    @Nested
    @DisplayName("the payload the OCTO endpoints actually write")
    class OctoDialect {

        @Test
        @DisplayName("an OCTO reservation is applied, not refused for a missing date")
        void octoPayloadIsUnderstood() {
            // The regression this class exists to prevent. OctoBookingController stores
            // availabilityId/contact/pricing; the processor read localDate/startTime/
            // guestName/grossMinor. Neither side was broken on its own, so every OCTO
            // reservation was recorded, refused as "missing a usable date or start
            // time", and reported as a healthy inbox row.
            ingestReturns(new BookingIngestClient.Result.Accepted("SKBG26Y", true));

            assertThat(processor.process(
                entry(ReservationInboxEntry.MessageType.CREATE, OCTO_PAYLOAD))).isTrue();

            verify(bookingIngestClient).ingest(
                eq("simulator"), eq("EXT-1"), eq(1L), eq(14L),
                eq(java.time.LocalDate.of(2026, 9, 1)),
                eq(java.time.LocalTime.of(18, 0)),
                eq(120),
                eq(2),                       // two unitItems, not the default of one
                eq("Asha Rao"),
                eq("asha@example.com"));
            // Priced from pricing.retail, so the receivable is right too.
            verify(inboxService).markApplied(9L, "SKBG26Y", 1L, "INR", 300000L);
        }

        @Test
        @DisplayName("an unreadable availabilityId is refused, not silently defaulted")
        void unusableAvailabilityIdRefused() {
            String bad = OCTO_PAYLOAD.replace("2026-09-01T18:00|120", "sometime-next-week");

            processor.process(entry(ReservationInboxEntry.MessageType.CREATE, bad));

            // Guessing a window would sell a slot nobody asked for.
            verifyNoInteractions(bookingIngestClient);
            verify(inboxService).markRejected(eq(9L), contains("date or start time"));
        }

        @Test
        @DisplayName("a reservation with no guest name is refused")
        void anonymousReservationRefused() {
            String nameless = OCTO_PAYLOAD.replace(
                "\"fullName\":\"Asha Rao\",", "");

            processor.process(entry(ReservationInboxEntry.MessageType.CREATE, nameless));

            // The venue has to know who is arriving.
            verifyNoInteractions(bookingIngestClient);
            verify(inboxService).markRejected(eq(9L), contains("guest name"));
        }
    }

    @Nested
    @DisplayName("OCTO confirmation, which carries no reservation detail")
    class Confirmation {

        /** POST /bookings/{uuid}/confirm has an optional body; resellers send none. */
        private static final String BARE_CONFIRM = "{\"uuid\":\"EXT-1\"}";

        @Test
        @DisplayName("confirms the booking the CREATE already made")
        void confirmsExistingBooking() {
            when(inboxRepository.findByConnectionIdAndExternalRefOrderByIdAsc(7L, "EXT-1"))
                .thenReturn(List.of(ReservationInboxEntry.builder().id(8L)
                    .status(ReservationInboxEntry.Status.APPLIED)
                    .bookingRef("SKBG26X").build()));

            assertThat(processor.process(
                entry(ReservationInboxEntry.MessageType.MODIFY, BARE_CONFIRM))).isTrue();

            // Sent down the ingestion path it would be refused as "no live listing
            // matches product 'null'", leaving a REJECTED row beside the booking it
            // successfully confirmed.
            verifyNoInteractions(bookingIngestClient);
            verify(inboxService).markApplied(9L, "SKBG26X", 1L, null, 0L);
        }

        @Test
        @DisplayName("a confirmation with nothing to confirm is refused, not invented")
        void orphanConfirmationRejected() {
            when(inboxRepository.findByConnectionIdAndExternalRefOrderByIdAsc(7L, "EXT-1"))
                .thenReturn(List.of());

            processor.process(entry(ReservationInboxEntry.MessageType.MODIFY, BARE_CONFIRM));

            verifyNoInteractions(bookingIngestClient);
            verify(inboxService).markRejected(eq(9L), contains("to confirm"));
        }

        @Test
        @DisplayName("a MODIFY that DOES describe a reservation still goes to booking-service")
        void completeModifyIsIngested() {
            // booking-service is idempotent on (externalSource, externalRef), so this
            // converges on the existing booking — and creates it if the CREATE never
            // arrived, which at-least-once delivery permits.
            ingestReturns(new BookingIngestClient.Result.Accepted("SKBG26Y", false));

            assertThat(processor.process(
                entry(ReservationInboxEntry.MessageType.MODIFY, OCTO_PAYLOAD))).isTrue();

            verify(bookingIngestClient).ingest(any(), any(), any(), any(), any(), any(),
                any(), anyInt(), any(), any());
        }
    }

    @Test
    @DisplayName("a business refusal is REJECTED, not retried forever")
    void refusalIsRejected() {
        ingestReturns(new BookingIngestClient.Result.Rejected("slot taken"));

        processor.process(entry(ReservationInboxEntry.MessageType.CREATE, FLAT_PAYLOAD));

        verify(inboxService).markRejected(9L, "slot taken");
        verify(inboxService, never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("a transport error is FAILED, which stays retryable")
    void transportErrorIsRetryable() {
        ingestReturns(new BookingIngestClient.Result.Failed("timeout"));
        when(inboxService.markFailed(eq(9L), any())).thenAnswer(i ->
            ReservationInboxEntry.builder().id(9L)
                .status(ReservationInboxEntry.Status.FAILED)
                .rejectReason(i.getArgument(1)).build());

        ReservationInboxEntry e = entry(ReservationInboxEntry.MessageType.CREATE, FLAT_PAYLOAD);
        processor.process(e);

        // Collapsing FAILED into REJECTED abandons a real reservation over a blip.
        assertThat(e.getStatus()).isEqualTo(ReservationInboxEntry.Status.FAILED);
        verify(inboxService, never()).markRejected(anyLong(), any());
    }

    @Nested
    @DisplayName("cancellations")
    class Cancellations {

        @Test
        @DisplayName("a CANCEL reaches the PMS instead of being politely refused")
        void cancelIsApplied() {
            // Previously answered "CANCEL is not yet applied automatically": the
            // traveller cancelled on the OTA, the venue kept holding the slot, and
            // nothing anywhere reported a problem.
            when(bookingIngestClient.cancel(eq("simulator"), eq("EXT-1"), any()))
                .thenReturn(new BookingIngestClient.Result.Accepted("SKBG26X", false));

            assertThat(processor.process(
                entry(ReservationInboxEntry.MessageType.CANCEL, OCTO_PAYLOAD))).isTrue();

            verify(inboxService).markApplied(eq(9L), eq("SKBG26X"), eq(1L), any(), anyLong());
        }

        @Test
        @DisplayName("a CANCEL is never sent down the creation path")
        void cancelIsNotACreation() {
            // Ingesting a cancellation would create a booking FROM a cancellation, the
            // exact inversion the ordering rules exist to prevent.
            when(bookingIngestClient.cancel(any(), any(), any()))
                .thenReturn(new BookingIngestClient.Result.Accepted("SKBG26X", false));

            processor.process(entry(ReservationInboxEntry.MessageType.CANCEL, OCTO_PAYLOAD));

            verify(bookingIngestClient, never())
                .ingest(any(), any(), any(), any(), any(), any(), any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("cancelling something booking-service never saw is REJECTED")
        void unknownCancellationRejected() {
            when(bookingIngestClient.cancel(any(), any(), any()))
                .thenReturn(new BookingIngestClient.Result.Rejected("No such reservation"));

            processor.process(entry(ReservationInboxEntry.MessageType.CANCEL, OCTO_PAYLOAD));

            // Permanent: a reservation booking-service has never seen will not start
            // existing on a retry.
            verify(inboxService).markRejected(9L, "No such reservation");
        }
    }

    @Test
    @DisplayName("a cancellation never creates a receivable")
    void cancellationCreatesNoReceivable() {
        when(bookingIngestClient.cancel(any(), any(), any()))
            .thenReturn(new BookingIngestClient.Result.Accepted("SKBG26X", false));

        processor.process(entry(ReservationInboxEntry.MessageType.CANCEL, OCTO_PAYLOAD));

        // Null currency and zero gross, even though the payload happens to carry a
        // price: booking the sale a second time on the way out would double what the
        // channel appears to owe.
        verify(inboxService).markApplied(9L, "SKBG26X", 1L, null, 0L);
    }

    @Test
    @DisplayName("a product with no LIVE listing is refused")
    void unmappedProductRefused() {
        when(listingRepository.findByBingeId(1L)).thenReturn(List.of(
            ListingMapping.builder().id(1L).bingeId(1L).eventTypeId(14L)
                .publishState(ListingMapping.PublishState.DRAFT).build()));

        // Resolving through the mapping rather than trusting an id in the payload is what
        // stops a reseller booking inventory it was never offered.
        processor.process(entry(ReservationInboxEntry.MessageType.CREATE, FLAT_PAYLOAD));

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
