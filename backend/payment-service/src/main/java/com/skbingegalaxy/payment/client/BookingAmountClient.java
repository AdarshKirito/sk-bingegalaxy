package com.skbingegalaxy.payment.client;

import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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
            @Value("${internal.api.secret}") String internalApiSecret,
            @Value("${services.booking.base-url:http://booking-service:8083}") String bookingBaseUrl) {
        this.restClient = restClientBuilder
            .baseUrl(bookingBaseUrl)
            .build();
        this.internalApiSecret = internalApiSecret;
    }

    /**
     * Fetches the remaining payable amount for a booking.
     * Returns null on failure (circuit-breaker / service down).
     */
    public BigDecimal getRemainingBalance(String bookingRef) {
        BookingSnapshot snap = fetchSnapshot(bookingRef);
        return snap == null ? null : snap.remainingBalance();
    }

    /**
     * Fetches the full booking snapshot (status + remaining balance) in one call.
     * Returns null on failure.
     */
    public BookingSnapshot fetchSnapshot(String bookingRef) {
        try {
            var response = restClient.get()
                .uri("/api/v1/bookings/internal/amount/{ref}", bookingRef)
                .header("X-Internal-Secret", internalApiSecret)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response != null && response.get("data") instanceof Map<?, ?> data) {
                Object remaining = data.get("remainingBalance");
                Object status = data.get("status");
                Object payCcy = data.get("paymentCurrencyCode");
                Object fxRate = data.get("fxRate");
                if (remaining != null) {
                    return new BookingSnapshot(
                        new BigDecimal(remaining.toString()),
                        status != null ? status.toString() : null,
                        payCcy != null ? payCcy.toString() : "INR",
                        fxRate != null ? new BigDecimal(fxRate.toString()) : BigDecimal.ONE);
                }
            }
            return null;
        } catch (HttpClientErrorException.NotFound nf) {
            // Booking-service authoritatively says the booking doesn't exist.
            // Surface this as a real 404 rather than burying it in a generic 503
            // — a non-existent booking is a client error, not infrastructure failure,
            // and conflating the two pages on-call SREs falsely.
            throw new ResourceNotFoundException("Booking", "ref", bookingRef);
        } catch (Exception e) {
            log.warn("Failed to fetch booking amount for {}: {}", bookingRef, e.getMessage());
            return null;
        }
    }

    /**
     * @param remainingBalance   payable balance in the BASE currency (INR)
     * @param status             booking status
     * @param paymentCurrencyCode currency the booking is to be paid in ("INR" if domestic)
     * @param fxRate             locked rate = foreign units per 1 INR (1 for INR bookings)
     */
    public record BookingSnapshot(BigDecimal remainingBalance, String status,
                                  String paymentCurrencyCode, BigDecimal fxRate) {}
}
