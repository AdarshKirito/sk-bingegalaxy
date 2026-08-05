package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.distribution.dto.EvaluateListingRequest;
import com.skbingegalaxy.distribution.dto.ListingDto;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.listing.ListingReadinessPolicy;
import com.skbingegalaxy.distribution.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Listings and publish gating (slice 4)")
class ListingServiceTest {

    @Mock private ListingMappingRepository listingRepository;
    @Mock private ConnectionDestinationRepository connectionDestinationRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private DestinationRepository destinationRepository;

    // The real policy, not a mock: its rules ARE the behaviour under test here.
    private final ListingReadinessPolicy policy = new ListingReadinessPolicy();

    private ListingService service() {
        return new ListingService(listingRepository, connectionDestinationRepository,
            connectionRepository, destinationRepository, policy);
    }

    private static EvaluateListingRequest req(Map<String, String> content) {
        EvaluateListingRequest r = new EvaluateListingRequest();
        r.setEventTypeId(14L);
        r.setConnectionDestinationId(3L);
        r.setContent(content);
        return r;
    }

    private void givenOwnedDestination(String code) {
        when(connectionDestinationRepository.findById(3L)).thenReturn(Optional.of(
            ConnectionDestination.builder().id(3L).connectionId(7L).destinationCode(code).build()));
        when(connectionRepository.findByIdAndBingeId(7L, 1L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L).build()));
    }

    @Test
    @DisplayName("an incomplete listing is BLOCKED and says what to fix")
    void incompleteIsBlockedWithReasons() {
        givenOwnedDestination("VIATOR");
        when(listingRepository.findByConnectionDestinationIdAndEventTypeId(3L, 14L))
            .thenReturn(Optional.empty());
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(destinationRepository.findAll()).thenReturn(List.of());

        ListingDto dto = service().evaluate(1L, req(Map.of("title", "Party Room")));

        assertThat(dto.getPublishState()).isEqualTo(ListingMapping.PublishState.BLOCKED);
        assertThat(dto.getReadinessPct()).isLessThan(100);
        // Blocked has to reach the person who can fix it, which means instructions.
        assertThat(dto.getBlockingReasons()).isNotEmpty();
    }

    @Test
    @DisplayName("a complete listing becomes READY, not LIVE")
    void completeBecomesReadyNotLive() {
        givenOwnedDestination("SIMULATOR");
        when(listingRepository.findByConnectionDestinationIdAndEventTypeId(3L, 14L))
            .thenReturn(Optional.empty());
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(destinationRepository.findAll()).thenReturn(List.of());

        ListingDto dto = service().evaluate(1L, req(Map.of("title", "T", "price", "100")));

        // Publishing is a decision, not a consequence of becoming complete.
        assertThat(dto.getPublishState()).isEqualTo(ListingMapping.PublishState.READY);
        assertThat(dto.getReadinessPct()).isEqualTo(100);
    }

    @Test
    @DisplayName("re-evaluating never demotes something already LIVE")
    void reEvaluationDoesNotDemoteLive() {
        givenOwnedDestination("VIATOR");
        when(listingRepository.findByConnectionDestinationIdAndEventTypeId(3L, 14L))
            .thenReturn(Optional.of(ListingMapping.builder().id(9L)
                .connectionDestinationId(3L).eventTypeId(14L).bingeId(1L)
                .publishState(ListingMapping.PublishState.LIVE).readinessPct(100).build()));
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(destinationRepository.findAll()).thenReturn(List.of());

        ListingDto dto = service().evaluate(1L, req(Map.of("title", "only")));

        // Pulling a live listing down is an explicit act, not a side effect of a check.
        assertThat(dto.getPublishState()).isEqualTo(ListingMapping.PublishState.LIVE);
        assertThat(dto.getReadinessPct()).isLessThan(100);
    }

    @Test
    @DisplayName("publishing below 100% is refused, quoting the reasons")
    void cannotPublishIncomplete() {
        when(listingRepository.findById(9L)).thenReturn(Optional.of(ListingMapping.builder()
            .id(9L).bingeId(1L).readinessPct(85)
            .blockingReasons(new String[]{"Add a meeting point."}).build()));

        assertThatThrownBy(() -> service().publish(1L, 9L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("85%")
            .hasMessageContaining("meeting point");
    }

    @Test
    @DisplayName("a listing cannot go live on a connection that is not ACTIVE")
    void connectionStateGatesPublishing() {
        when(listingRepository.findById(9L)).thenReturn(Optional.of(ListingMapping.builder()
            .id(9L).bingeId(1L).connectionDestinationId(3L).readinessPct(100).build()));
        when(connectionDestinationRepository.findById(3L)).thenReturn(Optional.of(
            ConnectionDestination.builder().id(3L).connectionId(7L).destinationCode("VIATOR").enabled(true).build()));
        when(connectionRepository.findById(7L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L)
                .status(Connection.ConnectionStatus.PAUSED).build()));

        // A live listing on a paused connection advertises inventory nobody can book.
        assertThatThrownBy(() -> service().publish(1L, 9L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PAUSED");
    }

    @Test
    @DisplayName("stop-sell blocks publishing to that destination")
    void stopSellBlocksPublishing() {
        when(listingRepository.findById(9L)).thenReturn(Optional.of(ListingMapping.builder()
            .id(9L).bingeId(1L).connectionDestinationId(3L).readinessPct(100).build()));
        when(connectionDestinationRepository.findById(3L)).thenReturn(Optional.of(
            ConnectionDestination.builder().id(3L).connectionId(7L)
                .destinationCode("VIATOR").enabled(true).stopSell(true).build()));
        when(connectionRepository.findById(7L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L)
                .status(Connection.ConnectionStatus.ACTIVE).build()));

        assertThatThrownBy(() -> service().publish(1L, 9L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Stop-sell");
    }

    @Test
    @DisplayName("a destination the venue never enabled cannot be published to")
    void disabledDestinationBlocksPublishing() {
        when(listingRepository.findById(9L)).thenReturn(Optional.of(ListingMapping.builder()
            .id(9L).bingeId(1L).connectionDestinationId(3L).readinessPct(100).build()));
        // enabled defaults to FALSE — distribution is opt-in at every level, and this is
        // the level the venue opts in at. Every other test in this file had to be
        // corrected to set it, because they were publishing to a destination nobody had
        // turned on and passing anyway.
        when(connectionDestinationRepository.findById(3L)).thenReturn(Optional.of(
            ConnectionDestination.builder().id(3L).connectionId(7L)
                .destinationCode("VIATOR").build()));
        when(connectionRepository.findById(7L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L)
                .status(Connection.ConnectionStatus.ACTIVE).build()));

        assertThatThrownBy(() -> service().publish(1L, 9L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not enabled");
    }

    @Test
    @DisplayName("a fully ready listing on an active connection publishes")
    void publishesWhenEverythingIsRight() {
        when(listingRepository.findById(9L)).thenReturn(Optional.of(ListingMapping.builder()
            .id(9L).bingeId(1L).connectionDestinationId(3L).readinessPct(100)
            .publishState(ListingMapping.PublishState.READY).build()));
        when(connectionDestinationRepository.findById(3L)).thenReturn(Optional.of(
            ConnectionDestination.builder().id(3L).connectionId(7L).destinationCode("VIATOR").enabled(true).build()));
        when(connectionRepository.findById(7L)).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L)
                .status(Connection.ConnectionStatus.ACTIVE).build()));
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(destinationRepository.findAll()).thenReturn(List.of());

        ListingDto dto = service().publish(1L, 9L);

        assertThat(dto.getPublishState()).isEqualTo(ListingMapping.PublishState.LIVE);
        assertThat(dto.getLastPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("another venue cannot evaluate or publish this venue's listings")
    void tenancyIsEnforced() {
        when(connectionDestinationRepository.findById(3L)).thenReturn(Optional.of(
            ConnectionDestination.builder().id(3L).connectionId(7L).destinationCode("VIATOR").enabled(true).build()));
        // Scoped through the OWNING connection, not by the destination id alone.
        when(connectionRepository.findByIdAndBingeId(7L, 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().evaluate(999L, req(Map.of("title", "x"))))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(listingRepository, never()).save(any());
    }

    @Test
    @DisplayName("publishing another venue's listing is not found")
    void cannotPublishAnotherVenuesListing() {
        when(listingRepository.findById(9L)).thenReturn(Optional.of(
            ListingMapping.builder().id(9L).bingeId(1L).readinessPct(100).build()));

        assertThatThrownBy(() -> service().publish(999L, 9L))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
