package com.skbingegalaxy.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private static final String SECRET =
            "c2tiLWJpbmdlLWdhbGF4eS1zdXBlci1zZWNyZXQta2V5LXByb2R1Y3Rpb24tY2hhbmdlLXRoaXM=";

    private JwtAuthenticationFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(filter, "jwtIssuer", "skbingegalaxy-auth");
        ReflectionTestUtils.setField(filter, "jwtAudience", "skbingegalaxy-web");
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private String generateToken(String subject, String role, String email, String firstName) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .claim("email", email)
                .claim("firstName", firstName)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(key)
                .compact();
    }

    private String generateExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        return Jwts.builder()
                .subject("1")
                .claim("role", "CUSTOMER")
                .claim("email", "user@test.com")
                .issuedAt(new Date(System.currentTimeMillis() - 7200_000))
                .expiration(new Date(System.currentTimeMillis() - 3600_000))
                .signWith(key)
                .compact();
    }

    // ── Public path tests ────────────────────────────────

    @Nested
    class PublicPathTests {

        @Test
        void loginPath_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/auth/login").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void registerPath_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/auth/register").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void actuatorPath_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/actuator/health").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void swaggerPath_requiresAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/swagger-ui/index.html").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void availabilityDates_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/availability/dates").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void bookedSlots_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/booked-slots?date=2026-04-08").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void notificationDeliveryWebhook_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/notifications/webhooks/delivery").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void notificationNonWebhookPath_requiresAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/notifications/my").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        // ── Booking-transfer magic-link endpoints (token IS the bearer) ──

        @Test
        void transferByTokenPreview_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/booking-transfers/by-token/some-opaque-token").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void transferByTokenAccept_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/booking-transfers/by-token/some-opaque-token/accept").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void transferByTokenDecline_bypassesAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/booking-transfers/by-token/some-opaque-token/decline").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void bookingTransfersOutsideByToken_requiresAuth() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/booking-transfers/123").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        void byTokenPrefixWithoutTrailingSlash_doesNotOverMatch() {
            // The allowlist entry ends with a slash; a crafted sibling segment
            // must not ride the public prefix.
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/booking-transfers/by-token-evil/x").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        void transferByTokenAccept_stripsSpoofedIdentityHeaders() {
            // The accept endpoint optionally reads X-User-Id to attribute the
            // transfer to a signed-in recipient. An anonymous caller must not
            // be able to inject it past the gateway.
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/booking-transfers/by-token/some-opaque-token/accept")
                    .header("X-User-Id", "999")
                    .header("X-User-Role", "SUPER_ADMIN")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(argThat(ex -> {
                HttpHeaders headers = ex.getRequest().getHeaders();
                return headers.getFirst("X-User-Id") == null
                        && headers.getFirst("X-User-Role") == null;
            }));
        }
    }

    // ── Authentication tests ─────────────────────────────

    @Nested
    class AuthenticationTests {

        @Test
        void noAuthHeader_returns401() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        void invalidBearerPrefix_returns401() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Basic abc123")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        void expiredToken_returns401() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + generateExpiredToken())
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        void tamperedToken_returns401() {
            String token = generateToken("1", "CUSTOMER", "user@test.com", "John");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token + "tampered")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        void validToken_forwardsUserHeaders() {
            String token = generateToken("42", "CUSTOMER", "john@test.com", "John");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(argThat(ex -> {
                HttpHeaders headers = ex.getRequest().getHeaders();
                return "42".equals(headers.getFirst("X-User-Id"))
                        && "john@test.com".equals(headers.getFirst("X-User-Email"))
                        && "CUSTOMER".equals(headers.getFirst("X-User-Role"))
                        && "John".equals(headers.getFirst("X-User-Name"));
            }));
        }

        @Test
        void validToken_nullFirstName_defaultsToCustomer() {
            String token = generateToken("10", "CUSTOMER", "user@test.com", null);
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(argThat(ex ->
                    "Customer".equals(ex.getRequest().getHeaders().getFirst("X-User-Name"))));
        }
    }

    // ── Admin authorization tests ────────────────────────

    @Nested
    class AdminAuthorizationTests {

        @Test
        void adminPath_customerRole_returns403() {
            String token = generateToken("1", "CUSTOMER", "user@test.com", "User");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/admin/dashboard")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(chain, never()).filter(any());
        }

        @Test
        void adminPath_adminRole_allowed() {
            String token = generateToken("1", "ADMIN", "admin@test.com", "Admin");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/admin/dashboard")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void adminPath_superAdminRole_allowed() {
            String token = generateToken("1", "SUPER_ADMIN", "sa@test.com", "Super");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/admin/dashboard")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void nonAdminPath_customerRole_allowed() {
            String token = generateToken("5", "CUSTOMER", "user@test.com", "User");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }
    }

    @Test
    void order_isNegativeOne() {
        assertThat(filter.getOrder()).isEqualTo(-1);
    }

    // ── Forced password-change (temp-password) gate ──────────

    @Nested
    class PasswordChangeGateTests {

        private String tempPasswordToken() {
            SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
            return Jwts.builder()
                    .subject("7")
                    .claim("role", "CUSTOMER")
                    .claim("email", "temp@test.com")
                    .claim("firstName", "Temp")
                    .claim("mustChangePassword", true)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 3600_000))
                    .signWith(key)
                    .compact();
        }

        @Test
        void tempPasswordToken_blockedOnNormalPath_with403AndHeader() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tempPasswordToken())
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange.getResponse().getHeaders().getFirst("X-Password-Change-Required"))
                    .isEqualTo("true");
            verify(chain, never()).filter(any());
        }

        @Test
        void tempPasswordToken_allowedOnChangePassword() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .put("/api/v1/auth/change-password")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tempPasswordToken())
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void tempPasswordToken_allowedOnProfile() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/auth/profile")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tempPasswordToken())
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }
    }

    @Nested
    class IssuerAudienceTests {

        private String generateTokenWithIssAud(String issuer, String audience) {
            SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
            var builder = Jwts.builder()
                    .subject("1")
                    .claim("role", "CUSTOMER")
                    .claim("email", "user@test.com")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 3600_000));
            if (issuer != null) builder.issuer(issuer);
            if (audience != null) builder.audience().add(audience).and();
            return builder.signWith(key).compact();
        }

        @Test
        void wrongIssuer_returns401() {
            String token = generateTokenWithIssAud("evil-issuer", "skbingegalaxy-web");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        void wrongAudience_returns401() {
            String token = generateTokenWithIssAud("skbingegalaxy-auth", "different-app");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        void correctIssAud_allowed() {
            String token = generateTokenWithIssAud("skbingegalaxy-auth", "skbingegalaxy-web");
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }
    }
}
