package com.skbingegalaxy.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The two gateway allow-lists that must agree, and the traversal that proved they did not.
 *
 * <p>The OCTO supplier surface needs an exemption in <b>both</b> filters:
 * {@code JwtAuthenticationFilter}, because a reseller's Bearer is its issued key and not
 * an SK JWT, and {@code CsrfProtectionFilter}, because a server-to-server caller has no
 * browser Origin. The lists are private constants in different classes and nothing links
 * them — the OCTO entry reached the CSRF filter first and the JWT filter only later, after
 * every legitimate reseller call had been answered 401 by a filter whose logs
 * distribution-service could not see.
 *
 * <p><b>They then disagreed a second way, and this is the one that mattered.</b> The JWT
 * filter normalized the path before matching; the CSRF filter used a raw
 * {@code startsWith}. So {@code POST /api/v1/distribution/octo/../connections} matched the
 * machine-to-machine prefix as a string and skipped CSRF — before the Origin check ever
 * ran — while the JWT filter normalized it to {@code /api/v1/distribution/connections},
 * found an admin endpoint and demanded a token. Authentication held and CSRF did not,
 * which is exactly the situation CSRF exists for: the victim is a signed-in admin whose
 * own browser attaches the session, driven by a page the attacker controls.
 *
 * <p>Neither filter was wrong on its own. There were two matchers, and nothing made them
 * agree. Both now call {@link GatewayPathMatching}; these tests are what keep it that way.
 */
@DisplayName("Machine-to-machine allow-list agreement")
class MachineToMachineAllowListTest {

    private static final String OCTO_PREFIX = "/api/v1/distribution/octo/";
    private static final String ALLOWED_ORIGIN = "https://app.skbingegalaxy.com";

    private CsrfProtectionFilter csrfFilter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        csrfFilter = new CsrfProtectionFilter(List.of(ALLOWED_ORIGIN), true, null);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    /** A cross-site POST: no Origin the gateway trusts, no CSRF token pair. */
    private MockServerWebExchange crossSitePost(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest
            .post(path)
            .header("Origin", "https://attacker.example")
            .build());
    }

    private HttpStatus statusAfterCsrf(String path) {
        MockServerWebExchange exchange = crossSitePost(path);
        csrfFilter.filter(exchange, chain).block();
        return HttpStatus.resolve(exchange.getResponse().getStatusCode() == null
            ? 200 : exchange.getResponse().getStatusCode().value());
    }

    @Nested
    @DisplayName("path traversal out of the exempt namespace")
    class Traversal {

        @Test
        @DisplayName("..\\ out of /octo/ no longer skips CSRF")
        void traversalOutOfOctoIsNotExempt() {
            // The bug, stated as a test. This path is not an OCTO endpoint: it normalizes
            // to /api/v1/distribution/connections, an admin surface. Exempting it let a
            // malicious page drive a signed-in admin's browser into creating or altering
            // distribution connections.
            assertThat(statusAfterCsrf("/api/v1/distribution/octo/../connections"))
                .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a deeper traversal is caught too, not just a single hop")
        void deeperTraversalIsNotExempt() {
            assertThat(statusAfterCsrf("/api/v1/distribution/octo/x/../../../bookings/admin/x"))
                .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a sibling namespace that merely starts with the same letters is not exempt")
        void siblingPrefixIsNotExempt() {
            // Boundary matching, not startsWith: /octopus is a different namespace, and
            // under the old raw prefix check it would have been exempt.
            assertThat(statusAfterCsrf("/api/v1/distribution/octopus/bookings"))
                .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("the genuine OCTO surface still works")
    class GenuineTraffic {

        @Test
        @DisplayName("a real reseller POST is still exempt from CSRF")
        void realOctoCallIsStillExempt() {
            // Tightening the matcher must not close the surface it exists to open. A
            // reseller has no browser Origin and cannot hold a CSRF cookie.
            MockServerWebExchange exchange = crossSitePost(OCTO_PREFIX + "bookings");
            csrfFilter.filter(exchange, chain).block();

            verify(chain).filter(exchange);
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("a lifecycle path carrying a reseller uuid is still exempt")
        void uuidBearingPathIsStillExempt() {
            // Why the entry is a prefix rather than an exact string: the uuid is the
            // reseller's own and no fixed path can enumerate it.
            MockServerWebExchange exchange =
                crossSitePost(OCTO_PREFIX + "bookings/6f1e-abc-99/confirm");
            csrfFilter.filter(exchange, chain).block();

            verify(chain).filter(exchange);
        }
    }

    @Nested
    @DisplayName("both filters resolve the OCTO namespace identically")
    class BothFiltersAgree {

        @Test
        @DisplayName("the shared matcher accepts real OCTO paths and refuses traversals")
        void sharedMatcherIsTheSingleSourceOfTruth() {
            assertThat(GatewayPathMatching.matchesNormalized(
                OCTO_PREFIX + "products", OCTO_PREFIX)).isTrue();
            assertThat(GatewayPathMatching.matchesNormalized(
                OCTO_PREFIX, OCTO_PREFIX)).isTrue();

            // The two that used to differ between the filters.
            assertThat(GatewayPathMatching.matchesNormalized(
                "/api/v1/distribution/octo/../connections", OCTO_PREFIX)).isFalse();
            assertThat(GatewayPathMatching.matchesNormalized(
                "/api/v1/distribution/octopus/x", OCTO_PREFIX)).isFalse();
        }

        @Test
        @DisplayName("an unparseable path does not throw, and still matches when it is not climbing")
        void unparseablePathDoesNotThrow() {
            // My first version of this test asserted false and failed, which was the
            // assertion being wrong rather than the code: normalizePath falls back to the
            // raw path, and this one genuinely is under the OCTO prefix. Recording the
            // real behaviour matters, because the tempting "unparseable matches nothing"
            // rule would demand a JWT on public paths whose decoded form contains a space
            // — a media filename, for instance — and URI.create rejects those too.
            assertThat(GatewayPathMatching.matchesNormalized(
                "/api/v1/distribution/octo/{bad brace}", OCTO_PREFIX)).isTrue();
        }

        @Test
        @DisplayName("but an unparseable path that is ALSO climbing is refused")
        void unparseableTraversalIsRefused() {
            // The case the raw-path fallback would otherwise reopen: parsing fails, so the
            // ".." survives, and a plain prefix match would exempt a path that resolves
            // outside the namespace. A surviving traversal segment means the path could
            // not be resolved AND is trying to climb — no allow-list should take that.
            assertThat(GatewayPathMatching.matchesNormalized(
                "/api/v1/distribution/octo/../connections{", OCTO_PREFIX)).isFalse();
        }

        @Test
        @DisplayName("a filename that merely contains dots is not mistaken for a traversal")
        void doubleDotInsideASegmentIsNotTraversal() {
            // Segment equality, not a substring search: "report..final.pdf" is a name, not
            // a climb, and rejecting it would break legitimate paths.
            assertThat(GatewayPathMatching.matchesNormalized(
                OCTO_PREFIX + "files/report..final.pdf", OCTO_PREFIX)).isTrue();
        }
    }
}
