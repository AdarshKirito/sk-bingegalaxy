package com.skbingegalaxy.payment.client;

import com.skbingegalaxy.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Razorpay API gateway client with Resilience4j circuit breaker + retry.
 * Uses the auto-configured RestClient.Builder which propagates B3 trace headers.
 * HTTP timeouts (connect=5s, read=5s) prevent hanging calls without requiring @TimeLimiter.
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
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClientBuilder
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    // Currencies whose smallest unit is NOT 1/100 of the major unit. Razorpay (like ISO-4217)
    // expects the amount in the currency's minor unit, so the ×100 assumption is wrong for
    // zero-decimal (¥, ₩) and three-decimal (Gulf dinar) currencies.
    private static final java.util.Set<String> ZERO_DECIMAL =
            java.util.Set.of("JPY", "KRW", "VND", "CLP", "XOF", "XAF", "PYG", "UGX", "RWF", "ISK");
    private static final java.util.Set<String> THREE_DECIMAL =
            java.util.Set.of("KWD", "BHD", "OMR", "JOD", "TND");

    /** Convert a major-unit amount to the currency's smallest gateway subunit. */
    private static long toSubunits(BigDecimal amount, String currency) {
        String c = currency == null ? "INR" : currency.trim().toUpperCase();
        int factor = ZERO_DECIMAL.contains(c) ? 1 : THREE_DECIMAL.contains(c) ? 1000 : 100;
        return amount.multiply(BigDecimal.valueOf(factor)).longValue();
    }

    @SuppressWarnings("unchecked")
    @Retry(name = "razorpay")
    @CircuitBreaker(name = "razorpay", fallbackMethod = "createOrderFallback")
    public String createOrder(BigDecimal amount, String currency, String receipt) {
        String credentials = basicAuth();

        // Idempotency: a previous attempt may have created the order but timed out
        // before we saw the response — @Retry would then create a duplicate order.
        // Razorpay does not dedup by receipt, so we look the receipt up first and
        // reuse a still-payable order with the same amount/currency.
        String existing = findReusableOrderByReceipt(receipt, toSubunits(amount, currency),
                currency != null ? currency : "INR", credentials);
        if (existing != null) {
            log.info("Reusing existing Razorpay order {} for receipt {} (retry after timeout)", existing, receipt);
            return existing;
        }

        Map<String, Object> body = Map.of(
                "amount", toSubunits(amount, currency),
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

    /**
     * Best-effort lookup of an existing, still-payable order with this receipt.
     * Returns null when none is reusable (or the lookup itself fails — the
     * subsequent create is then the source of truth).
     */
    @SuppressWarnings("unchecked")
    private String findReusableOrderByReceipt(String receipt, long amountSubunits,
                                              String currency, String credentials) {
        if (receipt == null || receipt.isBlank()) return null;
        try {
            Map<String, Object> response = restClient
                    .get()
                    .uri("https://api.razorpay.com/v1/orders?receipt={receipt}&count=5", receipt)
                    .header("Authorization", "Basic " + credentials)
                    .retrieve()
                    .body(Map.class);
            Object items = response != null ? response.get("items") : null;
            if (!(items instanceof java.util.List<?> list)) return null;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> order)) continue;
                String status = String.valueOf(order.get("status"));
                Object amt = order.get("amount");
                String curr = String.valueOf(order.get("currency"));
                boolean payable = "created".equals(status) || "attempted".equals(status);
                boolean sameAmount = amt instanceof Number n && n.longValue() == amountSubunits;
                if (payable && sameAmount && currency.equalsIgnoreCase(curr)) {
                    return (String) order.get("id");
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Order-by-receipt lookup failed for {} — proceeding to create: {}", receipt, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unused")
    private String createOrderFallback(BigDecimal amount, String currency, String receipt, Throwable t) {
        log.error("Circuit breaker OPEN for Razorpay — order creation failed for receipt={}: {}", receipt, t.getMessage());
        throw new BusinessException("Payment gateway is temporarily unavailable. Please try again later.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── Refunds ─────────────────────────────────────────────────────────────

    /** Outcome of a gateway refund call: the real Razorpay refund id + its status. */
    public record GatewayRefundResult(String refundId, String status) {
        /** Razorpay reports the refund as fully processed. */
        public boolean processed() { return "processed".equalsIgnoreCase(status); }
        /** Razorpay accepted the refund but it has not settled yet (webhook will confirm). */
        public boolean pending()   { return !processed() && !failed(); }
        public boolean failed()    { return "failed".equalsIgnoreCase(status); }
    }

    /**
     * Creates a real refund against a captured Razorpay payment.
     *
     * <p>Deliberately NOT annotated with {@code @Retry}: a timeout after Razorpay
     * accepted the refund followed by a blind retry would refund the customer
     * twice. Failures propagate to the caller, which records the refund attempt
     * as FAILED for the admin failed-refund queue (fail-closed — a refund is
     * never marked SUCCEEDED without a gateway-confirmed refund id).</p>
     *
     * @param gatewayPaymentId the captured payment ("pay_...") to refund against
     * @param amount           major-unit amount to refund
     * @param currency         ISO currency of the payment
     * @param internalRef      our internal reference stored as the refund receipt
     */
    @SuppressWarnings("unchecked")
    public GatewayRefundResult createRefund(String gatewayPaymentId, BigDecimal amount,
                                            String currency, String internalRef) {
        if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
            throw new BusinessException(
                "Cannot refund via gateway — payment has no gateway payment id", HttpStatus.CONFLICT);
        }
        Map<String, Object> body = Map.of(
                "amount", toSubunits(amount, currency),
                "receipt", internalRef != null ? internalRef : "",
                "notes", Map.of("internal_ref", internalRef != null ? internalRef : "")
        );

        Map<String, Object> response = restClient
                .post()
                .uri("https://api.razorpay.com/v1/payments/{paymentId}/refund", gatewayPaymentId)
                .header("Authorization", "Basic " + basicAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("id") == null) {
            throw new BusinessException(
                "Razorpay refund creation failed — no refund id returned", HttpStatus.BAD_GATEWAY);
        }
        String refundId = (String) response.get("id");
        String status = response.get("status") != null ? String.valueOf(response.get("status")) : "pending";
        log.info("Razorpay refund {} created against payment {} — status={}", refundId, gatewayPaymentId, status);
        return new GatewayRefundResult(refundId, status);
    }

    /**
     * Looks up a refund we may have already created against this payment, by
     * our stable receipt (PAY-006 ambiguity recovery). Returns the refund when
     * the provider has it, {@code EMPTY} when the provider authoritatively has
     * no refund with this receipt, and {@code null} when the lookup itself
     * failed (still ambiguous — caller must NOT treat that as absence).
     */
    @SuppressWarnings("unchecked")
    public Optional<GatewayRefundResult> findRefundByReceipt(String gatewayPaymentId, String receipt) {
        if (razorpayKeyId == null || razorpayKeyId.isBlank()
                || gatewayPaymentId == null || gatewayPaymentId.isBlank()
                || receipt == null || receipt.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> response = restClient
                    .get()
                    .uri("https://api.razorpay.com/v1/payments/{paymentId}/refunds", gatewayPaymentId)
                    .header("Authorization", "Basic " + basicAuth())
                    .retrieve()
                    .body(Map.class);
            Object items = response != null ? response.get("items") : null;
            if (items instanceof java.util.List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> refund)) continue;
                    boolean receiptMatch = receipt.equals(refund.get("receipt"));
                    if (!receiptMatch && refund.get("notes") instanceof Map<?, ?> notes) {
                        receiptMatch = receipt.equals(notes.get("internal_ref"));
                    }
                    if (receiptMatch) {
                        String id = String.valueOf(refund.get("id"));
                        String status = refund.get("status") != null
                            ? String.valueOf(refund.get("status")) : "pending";
                        return Optional.of(new GatewayRefundResult(id, status));
                    }
                }
            }
            return Optional.empty(); // authoritative: provider has no refund with this receipt
        } catch (Exception e) {
            log.warn("Refund-by-receipt lookup failed for payment {} receipt {}: {}",
                gatewayPaymentId, receipt, e.getMessage());
            return null; // ambiguous — the caller must not create a new refund on this signal
        }
    }

    /**
     * Fetches the current status of a Razorpay refund ("pending", "processed",
     * "failed") or null when it cannot be determined. Used by reconciliation to
     * settle PROCESSING refunds whose webhook never arrived.
     */
    @SuppressWarnings("unchecked")
    public String fetchRefundStatus(String gatewayRefundId) {
        if (razorpayKeyId == null || razorpayKeyId.isBlank()) {
            return null; // simulation mode — no real gateway to query
        }
        try {
            Map<String, Object> response = restClient
                    .get()
                    .uri("https://api.razorpay.com/v1/refunds/{refundId}", gatewayRefundId)
                    .header("Authorization", "Basic " + basicAuth())
                    .retrieve()
                    .body(Map.class);
            return response != null && response.get("status") != null
                ? String.valueOf(response.get("status")) : null;
        } catch (Exception e) {
            log.warn("Failed to fetch Razorpay refund status for {}: {}", gatewayRefundId, e.getMessage());
            return null;
        }
    }

    /**
     * Verifies the Razorpay checkout callback signature:
     * HMAC-SHA256(order_id + "|" + payment_id) with the API key secret.
     */
    public boolean verifyCheckoutSignature(String gatewayOrderId, String gatewayPaymentId, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                razorpayKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(
                (gatewayOrderId + "|" + (gatewayPaymentId == null ? "" : gatewayPaymentId))
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return java.security.MessageDigest.isEqual(
                sb.toString().getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Checkout signature verification error", e);
            return false;
        }
    }

    private String basicAuth() {
        return Base64.getEncoder()
                .encodeToString((razorpayKeyId + ":" + razorpayKeySecret).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Result of an order-status lookup that DISTINGUISHES "the provider told
     * us" from "we could not reach the provider" (PAY-007). A transport
     * failure must never be treated as an authoritative not-paid answer —
     * that is exactly the collapse that let an outage reopen checkout while
     * the first order could still capture.
     *
     * @param status        provider order status ("created" / "attempted" /
     *                      "paid" / "not_found"), or null when unknown
     * @param authoritative true only when the provider itself answered
     */
    public record OrderStatusLookup(String status, boolean authoritative) {
        public boolean paid() { return authoritative && "paid".equalsIgnoreCase(status); }
    }

    /**
     * Authoritative order-status lookup. 4xx from Razorpay (order id unknown)
     * counts as an authoritative "not_found"; 5xx/timeouts/connection errors
     * are non-authoritative — the caller must leave local state untouched.
     */
    @SuppressWarnings("unchecked")
    public OrderStatusLookup fetchOrderStatusAuthoritative(String gatewayOrderId) {
        if (razorpayKeyId == null || razorpayKeyId.isBlank()) {
            // Simulation mode: there is no provider, so nothing can be known.
            return new OrderStatusLookup(null, false);
        }
        try {
            Map<String, Object> response = restClient
                    .get()
                    .uri("https://api.razorpay.com/v1/orders/{orderId}", gatewayOrderId)
                    .header("Authorization", "Basic " + basicAuth())
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.containsKey("status")) {
                return new OrderStatusLookup((String) response.get("status"), true);
            }
            return new OrderStatusLookup(null, false);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.warn("Razorpay reports order {} as unknown ({})", gatewayOrderId, e.getStatusCode());
                return new OrderStatusLookup("not_found", true);
            }
            log.warn("Razorpay order-status lookup failed for {} ({}): {}",
                gatewayOrderId, e.getStatusCode(), e.getMessage());
            return new OrderStatusLookup(null, false);
        } catch (Exception e) {
            log.warn("Failed to fetch Razorpay order status for {}: {}", gatewayOrderId, e.getMessage());
            return new OrderStatusLookup(null, false);
        }
    }

    /**
     * Legacy convenience: order status string or null. Callers that need to
     * act on "not paid" must use {@link #fetchOrderStatusAuthoritative} —
     * null here conflates "unknown" with "absent".
     */
    public String fetchOrderStatus(String gatewayOrderId) {
        OrderStatusLookup lookup = fetchOrderStatusAuthoritative(gatewayOrderId);
        return lookup.authoritative() ? lookup.status() : null;
    }
}
