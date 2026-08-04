package com.skbingegalaxy.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-to-service surfaces must not be reachable from the internet.
 *
 * <p>Every service route in the gateway is a broad prefix — {@code Path=/api/v1/bookings/**}
 * also matches {@code /api/v1/bookings/internal/**}. Those endpoints are protected
 * downstream by {@code InternalApiAuthFilter} + {@code hasRole('SYSTEM')}, but that
 * shared secret is a credential for callers <em>already inside</em> the trusted
 * network, not an internet-facing bearer token. Internal endpoints also deliberately
 * return data the public API strips (a binge's {@code adminId}), and now include
 * reservation <em>ingestion</em>. Leaving the path routable would make a leaked secret
 * directly exploitable from the public internet.
 */
class InternalPathBlockingTest {

    private static final String SECRET =
            "c2tiLWJpbmdlLWdhbGF4eS1zdXBlci1zZWNyZXQta2V5LXByb2R1Y3Rpb24tY2hhbmdlLXRoaXM=";

    private JwtAuthenticationFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(null);
        ReflectionTestUtils.setField(filter, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(filter, "jwtIssuer", "skbingegalaxy-auth");
        ReflectionTestUtils.setField(filter, "jwtAudience", "skbingegalaxy-web");
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/bookings/internal/binges/1",
        "/api/v1/bookings/internal/amount/SKBG123",
        "/api/v1/bookings/internal/reservations",
        "/api/v1/availability/internal/check",
        "/api/v1/payments/internal/anything",
        "/api/v2/loyalty/internal/whatever",
    })
    @DisplayName("internal service paths are never routed from the edge")
    void internalPathsAreBlocked(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post(path).build());

        filter.filter(exchange, chain).block();

        // 404, not 403: probing must not confirm that the surface exists.
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("a client-supplied X-Internal-Secret never reaches a service")
    void internalSecretHeaderIsStripped() {
        // Public path so the request survives the JWT gate; the point is the header.
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/auth/login")
                .header("X-Internal-Secret", "guessed-or-leaked-value")
                .build());

        filter.filter(exchange, chain).block();

        // The filter forwards a MUTATED exchange down the chain; the original object
        // still holds the client's headers. Assert on what the service would actually
        // receive, which is the exchange the chain was handed.
        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());

        assertThat(forwarded.getValue().getRequest().getHeaders().getFirst("X-Internal-Secret"))
            .as("the service-to-service credential must never arrive from a client")
            .isNull();
    }

    @Test
    @DisplayName("path traversal cannot smuggle a request into an internal surface")
    void traversalIntoInternalIsBlocked() {
        // Without normalization this reads as .../binges/../internal/... and would
        // slip past a naive segment check.
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/bookings/binges/../internal/binges/1").build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/auth/login",
        "/api/v1/bookings/binges/internal-affairs-venue",
        "/api/v1/bookings/event-types",
    })
    @DisplayName("ordinary paths are unaffected, including ones containing the word 'internal'")
    void ordinaryPathsAreNotBlocked(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get(path).build());

        filter.filter(exchange, chain).block();

        // Either routed (public) or rejected for a missing JWT — never 404 from the
        // internal-path guard. A venue slug containing "internal" must still work.
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
    }
}
