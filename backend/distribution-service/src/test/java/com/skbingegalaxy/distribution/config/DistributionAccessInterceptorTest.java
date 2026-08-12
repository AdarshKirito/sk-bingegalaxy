package com.skbingegalaxy.distribution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.distribution.client.BingeAccessClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The tenancy boundary that the venue-facing distribution API did not have.
 *
 * <p>Every service method here was written "scoped by bingeId" — and the id came from a
 * header the browser sets, with nothing checking the caller had any relationship to that
 * venue. Changing one number let any authenticated admin read another venue's
 * connections, pause or revoke its sales channels, and issue itself a reseller key
 * against them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Distribution venue access")
class DistributionAccessInterceptorTest {

    private static final Long OWNER = 42L;
    private static final Long VENUE = 7L;

    @Mock private BingeAccessClient bingeAccessClient;

    private DistributionAccessInterceptor interceptor() {
        return new DistributionAccessInterceptor(bingeAccessClient, new ObjectMapper());
    }

    private MockHttpServletRequest request(String uri, String role, Long userId, Long bingeId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (role != null) request.addHeader("X-User-Role", role);
        if (userId != null) request.addHeader("X-User-Id", String.valueOf(userId));
        if (bingeId != null) request.addHeader("X-Binge-Id", String.valueOf(bingeId));
        return request;
    }

    private boolean preHandle(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        return interceptor().preHandle(request, response, new Object());
    }

    private void ownedByOwner(List<String> deniedModules) {
        when(bingeAccessClient.lookup(VENUE, OWNER))
            .thenReturn(new BingeAccessClient.Lookup.Found(VENUE, OWNER, deniedModules));
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("THE BUG: an admin of another venue is refused, however they set the header")
        void foreignVenueIsRefused() throws Exception {
            Long intruder = 99L;
            when(bingeAccessClient.lookup(VENUE, intruder))
                .thenReturn(new BingeAccessClient.Lookup.Found(VENUE, OWNER, List.of()));

            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean proceed = preHandle(
                request("/api/v1/distribution/connections", "ADMIN", intruder, VENUE), response);

            assertThat(proceed).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("do not manage this venue");
        }

        @Test
        @DisplayName("the venue's own admin proceeds")
        void ownerProceeds() throws Exception {
            ownedByOwner(List.of());

            assertThat(preHandle(request("/api/v1/distribution/connections", "ADMIN", OWNER, VENUE),
                new MockHttpServletResponse())).isTrue();
        }

        @Test
        @DisplayName("a super-admin is not asked, because operating across venues is their job")
        void superAdminBypasses() throws Exception {
            assertThat(preHandle(
                request("/api/v1/distribution/connections", "SUPER_ADMIN", 1L, VENUE),
                new MockHttpServletResponse())).isTrue();

            verify(bingeAccessClient, never()).lookup(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("fails closed")
    class FailsClosed {

        @Test
        @DisplayName("no venue header is a refusal, not a pass")
        void missingVenueIsRefused() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            // Omitting the header must not be a way around the check — that is how the
            // equivalent module gate elsewhere in the platform was bypassed.
            assertThat(preHandle(
                request("/api/v1/distribution/connections", "ADMIN", OWNER, null), response)).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("an unreachable booking-service is 503, not a silent pass and not a 403")
        void unavailableIsFiveOhThree() throws Exception {
            when(bingeAccessClient.lookup(VENUE, OWNER))
                .thenReturn(new BingeAccessClient.Lookup.Unavailable("SocketTimeoutException"));

            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(preHandle(
                request("/api/v1/distribution/connections", "ADMIN", OWNER, VENUE), response)).isFalse();

            // 503 rather than 403: the caller may well be entitled, and a permissions
            // error would send them hunting for a problem that does not exist. What must
            // never happen is the outage disabling the check.
            assertThat(response.getStatus()).isEqualTo(503);
        }

        @Test
        @DisplayName("a venue that does not exist is refused")
        void unknownVenueIsRefused() throws Exception {
            when(bingeAccessClient.lookup(VENUE, OWNER))
                .thenReturn(new BingeAccessClient.Lookup.NotFound());

            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(preHandle(
                request("/api/v1/distribution/connections", "ADMIN", OWNER, VENUE), response)).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("the DISTRIBUTION module grant")
    class ModuleGrant {

        @Test
        @DisplayName("an owner without the grant is refused — hiding the menu item is not enforcement")
        void deniedModuleIsRefused() throws Exception {
            ownedByOwner(List.of("DISTRIBUTION"));

            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(preHandle(
                request("/api/v1/distribution/connections", "ADMIN", OWNER, VENUE), response)).isFalse();

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("disabled by Super Admin");
        }

        @Test
        @DisplayName("an unrelated denied module does not block distribution")
        void otherDenialsAreIrrelevant() throws Exception {
            ownedByOwner(List.of("REPORTS", "MESSAGES"));

            assertThat(preHandle(request("/api/v1/distribution/listings", "ADMIN", OWNER, VENUE),
                new MockHttpServletResponse())).isTrue();
        }
    }

    @Nested
    @DisplayName("what the boundary deliberately does not cover")
    class Exclusions {

        @Test
        @DisplayName("the OCTO reseller seam, which has no admin and no venue header")
        void octoIsExcluded() {
            // Another company's system authenticating with a per-reseller key that
            // resolves to a connection. Applying an admin ownership check would 403 every
            // reseller call, because there is no admin to check.
            assertThat(DistributionAccessInterceptor.isVenueFacing(
                "/api/v1/distribution/octo/bookings")).isFalse();
        }

        @Test
        @DisplayName("the internal seam, already gated on the shared secret")
        void internalIsExcluded() {
            assertThat(DistributionAccessInterceptor.isVenueFacing(
                "/api/v1/distribution/internal/anything")).isFalse();
        }

        @Test
        @DisplayName("but everything else under the prefix is covered by default")
        void everythingElseIsCovered() {
            // Included by default so a new endpoint is guarded the moment it is written,
            // rather than when someone remembers to add it to a list — which is how the
            // gap this class closes came about.
            assertThat(DistributionAccessInterceptor.isVenueFacing(
                "/api/v1/distribution/settlements")).isTrue();
            assertThat(DistributionAccessInterceptor.isVenueFacing(
                "/api/v1/distribution/a-slice-nobody-has-written-yet")).isTrue();
            assertThat(DistributionAccessInterceptor.isVenueFacing("/actuator/health")).isFalse();
        }
    }
}
