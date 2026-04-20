package com.skbingegalaxy.payment.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * HTTP client for calling booking-service's internal API.
 * Uses the shared internal API secret for authentication.
 */
@Component
@Slf4j
public class BookingAmountClient {

    private final RestClient restClient;
    private final String internalApiSecret;

    public BookingAmountClient(
            RestClient.Builder restClientBuilder,
            @Value("${internal.api.secret}") String internalApiSecret) {
        this.restClient = restClientBuilder
            .baseUrl("http://booking-service")
            .build();
        this.internalApiSecret = internalApiSecret;
    }

    /**
     * Fetches the remaining payable amount for a booking.
     * Returns null on failure (circuit-breaker / service down).
     */
    public BigDecimal getRemainingBalance(String bookingRef) {
        try {
            var response = restClient.get()
                .uri("/api/v1/bookings/internal/amount/{ref}", bookingRef)
                .header("X-Internal-Secret", internalApiSecret)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response != null && response.get("data") instanceof Map<?, ?> data) {
                Object remaining = data.get("remainingBalance");
                if (remaining != null) {
                    return new BigDecimal(remaining.toString());
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to fetch booking amount for {}: {}", bookingRef, e.getMessage());
            return null;
        }
    }
}
