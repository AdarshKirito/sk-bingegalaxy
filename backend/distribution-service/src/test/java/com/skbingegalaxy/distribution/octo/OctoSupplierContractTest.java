package com.skbingegalaxy.distribution.octo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.distribution.client.AvailabilityClient;
import com.skbingegalaxy.distribution.client.BookingIngestClient;
import com.skbingegalaxy.distribution.client.EventTypeClient;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import com.skbingegalaxy.distribution.entity.ListingMapping;
import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import com.skbingegalaxy.distribution.repository.ConnectionDestinationRepository;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
import com.skbingegalaxy.distribution.repository.ListingMappingRepository;
import com.skbingegalaxy.distribution.repository.ReservationInboxRepository;
import com.skbingegalaxy.distribution.service.InboxProcessor;
import com.skbingegalaxy.distribution.service.ReservationInboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The three halves of the supplier API, joined — the test that was missing.
 *
 * <p><b>Why this class exists.</b> Three bugs shipped in one commit, and they shared one
 * shape: two halves that each worked alone and did not agree with each other.
 *
 * <ol>
 *   <li>{@code ResellerAuthenticator} resolved the <i>outbound</i> credential pointer,
 *       which is NULL by construction for a platform-managed connection. No reseller could
 *       authenticate, so the entire OCTO surface was unreachable.</li>
 *   <li>{@code InboxProcessor} read {@code localDate}/{@code guestName};
 *       {@code OctoBookingController} wrote {@code availabilityId}/{@code contact}. Every
 *       reservation was recorded and then politely refused.</li>
 *   <li>The availability endpoints never emitted the {@code availabilityId} that the
 *       booking endpoint requires, so nothing this API returned could be booked with.</li>
 * </ol>
 *
 * <p>Every one of those passed a full suite of per-class tests, because a per-class test
 * asks whether a component behaves — never whether two components <i>agree</i>. The
 * boundary checks passed too: the surface answered 401 exactly as expected, and 401 was
 * the only answer it was capable of giving.
 *
 * <p><b>The rule this class enforces: nothing here writes a payload.</b> The
 * {@code availabilityId} is the one the availability endpoint published. The inbox payload
 * is the exact JSON the booking controller serialised, captured off the call. An invented
 * fixture would agree with whatever it was written to agree with — which is precisely how
 * bug 2 survived a test named for it. This is the in-process form of the external
 * simulator, and it fails if any single link stops matching its neighbour.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OCTO supplier contract: availability -> booking -> canonical booking")
class OctoSupplierContractTest {

    @Mock private ResellerAuthenticator resellerAuthenticator;
    @Mock private AvailabilityClient availabilityClient;
    @Mock private EventTypeClient eventTypeClient;
    @Mock private ConnectionDestinationRepository connectionDestinationRepository;
    @Mock private ListingMappingRepository listingRepository;
    @Mock private ReservationInboxService inboxService;
    @Mock private ReservationInboxRepository inboxRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private BookingIngestClient bookingIngestClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OctoAvailabilityController availabilityController;
    private OctoBookingController bookingController;
    private InboxProcessor inboxProcessor;

    private static final Long CONNECTION_ID = 7L;
    private static final Long BINGE_ID = 1L;
    private static final Long EVENT_TYPE_ID = 14L;
    private static final String PRODUCT_ID = "14";

    /** Relative, so the test does not quietly start passing a past date next year. */
    private final LocalDate day = LocalDate.now().plusDays(3);

    private static final int START_MINUTE = 18 * 60;   // 18:00
    private static final int DURATION = 120;

    private final Connection connection = Connection.builder()
        .id(CONNECTION_ID).bingeId(BINGE_ID).providerCode("SIMULATOR")
        .status(Connection.ConnectionStatus.ACTIVE).build();

    @BeforeEach
    void wireTheRealComponents() {
        ResellerRateLimiter rateLimiter = new ResellerRateLimiter();
        ReflectionTestUtils.setField(rateLimiter, "requestsPerMinute", 10_000);

        // Real controllers and a real processor. Only the things across a network or a
        // database boundary are mocked; every component under test is the production one.
        availabilityController = new OctoAvailabilityController(
            resellerAuthenticator, rateLimiter, availabilityClient, eventTypeClient,
            connectionDestinationRepository, listingRepository);
        bookingController = new OctoBookingController(
            resellerAuthenticator, rateLimiter, inboxService, objectMapper);
        inboxProcessor = new InboxProcessor(
            inboxRepository, connectionRepository, listingRepository, inboxService,
            bookingIngestClient, objectMapper);

        lenient().when(resellerAuthenticator.authenticate(any()))
            .thenReturn(Optional.of(connection));
        lenient().when(connectionRepository.findById(CONNECTION_ID))
            .thenReturn(Optional.of(connection));
    }

    private ListingMapping liveListing() {
        return ListingMapping.builder().id(1L).connectionDestinationId(3L)
            .bingeId(BINGE_ID).eventTypeId(EVENT_TYPE_ID)
            .publishState(ListingMapping.PublishState.LIVE).readinessPct(100).build();
    }

    /**
     * A day with four consecutive free half-hour cells from 18:00 — exactly enough for
     * one two-hour window and no more, so the expected output is unambiguous.
     */
    private Map<String, Object> freeEveningDay() {
        List<Map<String, Object>> slots = List.of(
            Map.of("startMinute", START_MINUTE, "available", true),
            Map.of("startMinute", START_MINUTE + 30, "available", true),
            Map.of("startMinute", START_MINUTE + 60, "available", true),
            Map.of("startMinute", START_MINUTE + 90, "available", true));
        return Map.of("date", day.toString(), "closed", false,
                      "fullyBlocked", false, "availableSlots", slots);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> body(ResponseEntity<?> response) {
        return (List<Map<String, Object>>) response.getBody();
    }

    /** Step 1: ask the supplier what may be bought, and take the token it hands back. */
    private String publishedAvailabilityId() {
        when(connectionDestinationRepository.findByConnectionId(CONNECTION_ID))
            .thenReturn(List.of(ConnectionDestination.builder()
                .id(3L).connectionId(CONNECTION_ID).destinationCode("SIMULATOR")
                .enabled(true).build()));
        when(listingRepository.findByConnectionDestinationIdIn(List.of(3L)))
            .thenReturn(List.of(liveListing()));
        when(eventTypeClient.rulesFor(BINGE_ID, EVENT_TYPE_ID)).thenReturn(Optional.of(
            new EventTypeClient.BookingRules(EVENT_TYPE_ID, "Main hall",
                List.of(DURATION), null, null)));
        when(availabilityClient.slots(eq(BINGE_ID), any(), any()))
            .thenReturn(List.of(freeEveningDay()));

        OctoAvailabilityController.AvailabilityRequest request =
            new OctoAvailabilityController.AvailabilityRequest();
        request.setProductId(PRODUCT_ID);
        request.setLocalDate(day);

        List<Map<String, Object>> windows = body(availabilityController.availability("Bearer k", request));

        // If this is empty the reseller has nothing to book against, which is bug 3.
        assertThat(windows).hasSize(1);
        return (String) windows.get(0).get("id");
    }

    /**
     * Step 2: book that token, and capture the bytes the controller actually stores —
     * not a payload this test invented.
     */
    private String storedPayloadForBooking(String availabilityId) {
        when(inboxService.receive(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(ReservationInboxEntry.builder()
                .id(9L).connectionId(CONNECTION_ID).destinationCode("SIMULATOR")
                .externalRef("EXT-1").messageType(ReservationInboxEntry.MessageType.CREATE)
                .status(ReservationInboxEntry.Status.RECEIVED).build());

        OctoBookingController.OctoBookingRequest request =
            new OctoBookingController.OctoBookingRequest();
        request.setUuid("EXT-1");
        request.setProductId(PRODUCT_ID);
        request.setAvailabilityId(availabilityId);

        OctoBookingController.Contact contact = new OctoBookingController.Contact();
        contact.setFullName("Asha Rao");
        contact.setEmailAddress("asha@example.com");
        request.setContact(contact);
        request.setUnitItems(List.of(Map.of("unitId", "adult"), Map.of("unitId", "adult")));

        OctoBookingController.Pricing pricing = new OctoBookingController.Pricing();
        pricing.setRetail(300_000L);
        pricing.setCurrency("INR");
        request.setPricing(pricing);

        bookingController.reserve("Bearer k", request);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(inboxService).receive(eq(CONNECTION_ID), eq("SIMULATOR"), eq("EXT-1"),
            eq(ReservationInboxEntry.MessageType.CREATE), any(), any(), payload.capture());
        return payload.getValue();
    }

    @Test
    @DisplayName("a window this API published can be booked, and becomes the same window downstream")
    void theThreeHalvesAgree() {
        String availabilityId = publishedAvailabilityId();
        String storedPayload = storedPayloadForBooking(availabilityId);

        // Step 3: drain the inbox through the real production entry point, reading the
        // exact bytes the booking controller wrote.
        // Paged: the drain reads a bounded batch, so that one backlog cannot be loaded
        // into memory in full on a sweep that runs every 30 seconds.
        when(inboxRepository.findByStatusInOrderByReceivedAtAsc(
                eq(List.of(ReservationInboxEntry.Status.RECEIVED)), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(ReservationInboxEntry.builder()
                    .id(9L).connectionId(CONNECTION_ID).destinationCode("SIMULATOR")
                    .externalRef("EXT-1").messageType(ReservationInboxEntry.MessageType.CREATE)
                    .status(ReservationInboxEntry.Status.RECEIVED)
                    .payloadJson(storedPayload).build())));
        when(listingRepository.findByBingeId(BINGE_ID)).thenReturn(List.of(liveListing()));
        when(bookingIngestClient.ingest(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any(), any())).thenReturn(new BookingIngestClient.Result.Accepted("SKBG26X", true));

        assertThat(inboxProcessor.processOutstanding()).isEqualTo(1);

        // The window that reaches booking-service is the window the API advertised. Any
        // drift between the codec that encodes the token and the processor that decodes
        // it lands here rather than on a traveller's arrival day.
        verify(bookingIngestClient).ingest(
            eq("simulator"), eq("EXT-1"), eq(BINGE_ID), eq(EVENT_TYPE_ID),
            eq(day), eq(LocalTime.of(18, 0)), eq(DURATION),
            eq(2), eq("Asha Rao"), eq("asha@example.com"));
        verify(inboxService).markApplied(9L, "SKBG26X", BINGE_ID, "INR", 300_000L);
    }

    @Test
    @DisplayName("the availability token is opaque to the reseller but decodable by the supplier")
    void thePublishedTokenIsTheOneTheCodecUnderstands() {
        String availabilityId = publishedAvailabilityId();

        // Bug 3 in isolation: the booking endpoint requires an availabilityId the codec
        // can read. Publishing a token the supplier's own decoder rejects would leave
        // every reservation refused as "missing a usable date or start time" — the
        // symptom bug 2 produced, from an entirely different cause.
        assertThat(AvailabilityIdCodec.decode(availabilityId)).hasValueSatisfying(window -> {
            assertThat(window.start()).isEqualTo(day.atTime(18, 0));
            assertThat(window.durationMinutes()).isEqualTo(DURATION);
        });
    }
}
