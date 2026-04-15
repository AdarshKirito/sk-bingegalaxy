package com.skbingegalaxy.payment.client;

import com.skbingegalaxy.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Razorpay API gateway client with Resilience4j circuit breaker.
 * Uses the auto-configured RestClient.Builder which propagates B3 trace headers.
 */
@Component
@Slf4j
public class RazorpayGatewayClient {

    private final RestClient restClient;

    @Value("${app.razorpay.key-id:}")
    private String razorpayKeyId;

    @Value("${app.razorpay.key-secret}")
    private String razorpayKeySecret;

    public RazorpayGatewayClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "razorpay", fallbackMethod = "createOrderFallback")
    public String createOrder(BigDecimal amount, String currency, String receipt) {
        String credentials = Base64.getEncoder()
                .encodeToString((razorpayKeyId + ":" + razorpayKeySecret).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = Map.of(
                "amount", amount.multiply(BigDecimal.valueOf(100)).longValue(),
                "currency", currency != null ? currency : "INR",
                "receipt", receipt
        );

        Map<String, Object> response = restClient
                .post()
                .uri("https://api.razorpay.com/v1/orders")
                .header("Authorization", "Basic " + credentials)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response != null && response.containsKey("id")) {
            return (String) response.get("id");
        }
        throw new BusinessException("Razorpay order creation failed — no id returned", HttpStatus.BAD_GATEWAY);
    }

    @SuppressWarnings("unused")
    private String createOrderFallback(BigDecimal amount, String currency, String receipt, Throwable t) {
        log.error("Circuit breaker OPEN for Razorpay — order creation failed for receipt={}: {}", receipt, t.getMessage());
        throw new BusinessException("Payment gateway is temporarily unavailable. Please try again later.", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
