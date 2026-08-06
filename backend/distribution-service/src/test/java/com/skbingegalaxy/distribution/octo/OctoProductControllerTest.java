package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import com.skbingegalaxy.distribution.entity.ListingMapping;
import com.skbingegalaxy.distribution.repository.ConnectionDestinationRepository;
import com.skbingegalaxy.distribution.repository.ListingMappingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OCTO product catalogue")
class OctoProductControllerTest {

    @Mock private ResellerAuthenticator resellerAuthenticator;
    /**
     * A real limiter, not a mock: the default per-minute budget is far above anything a
     * test issues, so it never interferes — and using the real one means these tests
     * would notice if throttling were ever applied before authentication, which would
     * let an anonymous flood spend a real reseller's budget.
     */
    @org.mockito.Spy private ResellerRateLimiter rateLimiter = permissiveLimiter();
    @Mock private ConnectionDestinationRepository connectionDestinationRepository;
    @Mock private ListingMappingRepository listingRepository;
    @InjectMocks private OctoProductController controller;

    private static ResellerRateLimiter permissiveLimiter() {
        ResellerRateLimiter limiter = new ResellerRateLimiter();
        org.springframework.test.util.ReflectionTestUtils
            .setField(limiter, "requestsPerMinute", 10_000);
        return limiter;
    }

    private void authenticated() {
        when(resellerAuthenticator.authenticate(any())).thenReturn(Optional.of(
            Connection.builder().id(7L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.ACTIVE).build()));
    }

    private static ConnectionDestination dest(boolean enabled) {
        return ConnectionDestination.builder().id(3L).connectionId(7L)
            .destinationCode("SIMULATOR").enabled(enabled).build();
    }

    private static ListingMapping listing(ListingMapping.PublishState state) {
        return ListingMapping.builder().id(1L).connectionDestinationId(3L)
            .eventTypeId(14L).bingeId(1L).publishState(state).readinessPct(100).build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> body(ResponseEntity<?> r) {
        return (List<Map<String, Object>>) r.getBody();
    }

    @Test
    @DisplayName("an unauthenticated reseller sees nothing and is told nothing")
    void unauthenticatedRefused() {
        when(resellerAuthenticator.authenticate(any())).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.products("Bearer nope");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(listingRepository);
    }

    @Test
    @DisplayName("only LIVE listings are published to a reseller")
    void onlyLiveListingsArePublished() {
        authenticated();
        when(connectionDestinationRepository.findByConnectionId(7L)).thenReturn(List.of(dest(true)));
        when(listingRepository.findByConnectionDestinationIdIn(List.of(3L))).thenReturn(List.of(
            listing(ListingMapping.PublishState.LIVE),
            listing(ListingMapping.PublishState.BLOCKED),
            listing(ListingMapping.PublishState.DRAFT),
            listing(ListingMapping.PublishState.PAUSED)));

        // Publishing anything else would put inventory on sale that the readiness rules,
        // the ck_live_requires_ready CHECK and the operator all agree is not ready.
        assertThat(body(controller.products("Bearer ok"))).hasSize(1);
    }

    @Test
    @DisplayName("a destination the venue disabled contributes nothing")
    void disabledDestinationIsExcluded() {
        authenticated();
        when(connectionDestinationRepository.findByConnectionId(7L)).thenReturn(List.of(dest(false)));

        // Distribution is opt-in at every level; a disabled pairing is not on sale.
        assertThat(body(controller.products("Bearer ok"))).isEmpty();
        verifyNoInteractions(listingRepository);
    }

    @Test
    @DisplayName("private hire is priced per BOOKING with START_TIME availability")
    void productShapeMatchesWholeSpaceHire() {
        authenticated();
        when(connectionDestinationRepository.findByConnectionId(7L)).thenReturn(List.of(dest(true)));
        when(listingRepository.findByConnectionDestinationIdIn(any()))
            .thenReturn(List.of(listing(ListingMapping.PublishState.LIVE)));

        Map<String, Object> product = body(controller.products("Bearer ok")).get(0);

        // Not defaults — this is what whole-space hire IS. The venue is sold exclusively
        // for a window, so price does not scale with guests and availability is a set of
        // permitted start times rather than opening hours with a unit count.
        assertThat(product).containsEntry("pricingPer", "BOOKING")
                           .containsEntry("availabilityType", "START_TIME");
    }

    @Test
    @DisplayName("a short cache header is sent, because resellers poll hard")
    void cacheHeaderIsSent() {
        authenticated();
        when(connectionDestinationRepository.findByConnectionId(7L)).thenReturn(List.of());

        // DIST-R6: 365-day polling across every product melts booking-service. A cache
        // header is the cheapest defence that a well-behaved reseller actually honours.
        assertThat(controller.products("Bearer ok").getHeaders().getCacheControl())
            .contains("max-age=300");
    }
}
