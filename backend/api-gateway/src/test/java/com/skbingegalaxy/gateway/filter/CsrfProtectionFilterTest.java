package com.skbingegalaxy.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
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

class CsrfProtectionFilterTest {

    private static final String ALLOWED_ORIGIN = "https://app.skbingegalaxy.com";

    private CsrfProtectionFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new CsrfProtectionFilter(List.of(ALLOWED_ORIGIN), true, null);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Nested
    class WebhookExemptions {

        @Test
        void paymentCallback_bypassesCsrf() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/payments/callback").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void razorpayWebhook_bypassesCsrf() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/payments/webhooks/razorpay").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void notificationDeliveryWebhook_bypassesCsrf() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/notifications/webhooks/delivery").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void nonWebhookNotificationPost_isNotExempt() {
            // A server-to-server caller without Origin/Referer must be rejected
            // on a non-webhook path — exemption is exact-match, not prefix.
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/notifications/send").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(chain, never()).filter(any());
        }
    }

    @Nested
    class StandardCsrfBehaviour {

        @Test
        void safeMethod_passesAndMintsCookie() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/bookings/my").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
            assertThat(exchange.getResponse().getHeaders().getFirst("Set-Cookie"))
                    .contains("XSRF-TOKEN=");
        }

        @Test
        void stateChangingPost_badOrigin_rejected() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/bookings")
                    .header("Origin", "https://evil.example.com")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(chain, never()).filter(any());
        }

        @Test
        void stateChangingPost_goodOriginMatchingTokens_passes() {
            String token = "test-csrf-token-value";
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/bookings")
                    .header("Origin", ALLOWED_ORIGIN)
                    .header(CsrfProtectionFilter.CSRF_HEADER, token)
                    .cookie(new org.springframework.http.HttpCookie(
                            CsrfProtectionFilter.CSRF_COOKIE, token))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            verify(chain).filter(any());
        }

        @Test
        void stateChangingPost_tokenMismatch_rejected() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/bookings")
                    .header("Origin", ALLOWED_ORIGIN)
                    .header(CsrfProtectionFilter.CSRF_HEADER, "header-token")
                    .cookie(new org.springframework.http.HttpCookie(
                            CsrfProtectionFilter.CSRF_COOKIE, "cookie-token"))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(chain, never()).filter(any());
        }
    }
}
