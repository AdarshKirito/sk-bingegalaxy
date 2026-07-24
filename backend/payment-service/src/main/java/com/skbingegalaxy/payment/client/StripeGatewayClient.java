package com.skbingegalaxy.payment.client;

import com.skbingegalaxy.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Stripe API client, hand-rolled over {@link RestClient} in the same style as
 * {@link RazorpayGatewayClient} — deliberately no {@code stripe-java} SDK, so the
 * service keeps a single HTTP idiom, no extra supply-chain surface, and no SDK
 * version coupling.
 *
 * <p><b>Connect model: direct charges on the connected account.</b> Every call
 * that moves money passes {@code Stripe-Account: acct_…}, which makes the charge
 * happen <em>on the venue's own Stripe account, in the venue's country</em>. That
 * is what allows a customer anywhere to pay an Indian venue with UPI: local
 * payment methods are only available to an account domiciled in that country.
 * The platform's cut rides along as {@code application_fee_amount}.
 *
 * <p>Stripe's API is form-encoded (not JSON), and nested/array parameters use the
 * {@code key[0]}/{@code key[sub]} bracket convention — see {@link #form()} usage.
 */
@Component
@Slf4j
public class StripeGatewayClient {

    private static final String API = "https://api.stripe.com/v1";

    private final RestClient restClient;

    @Value("${app.stripe.secret-key:}")
    private String secretKey;

    @Value("${app.stripe.webhook-secret:}")
    private String webhookSecret;

    /**
     * Platform commission in basis points (100 = 1%). Applied as Stripe's
     * {@code application_fee_amount} on every direct charge.
     */
    @Value("${app.stripe.application-fee-bps:0}")
    private int applicationFeeBps;

    public StripeGatewayClient(RestClient.Builder restClientBuilder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(10));
        this.restClient = restClientBuilder
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    /** True when Stripe credentials are configured; otherwise the provider stays dormant. */
    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    // ── Amount conversion ───────────────────────────────────────────────────
    // Stripe, like Razorpay, expects the smallest currency unit. The ×100
    // assumption is wrong for zero-decimal (JPY, KRW) and three-decimal (Gulf
    // dinar) currencies, so mirror the same tables used for Razorpay.
    private static final java.util.Set<String> ZERO_DECIMAL =
            java.util.Set.of("JPY", "KRW", "VND", "CLP", "XOF", "XAF", "PYG", "UGX", "RWF", "ISK");
    private static final java.util.Set<String> THREE_DECIMAL =
            java.util.Set.of("KWD", "BHD", "OMR", "JOD", "TND");

    static long toSubunits(BigDecimal amount, String currency) {
        String c = currency == null ? "USD" : currency.trim().toUpperCase();
        int factor = ZERO_DECIMAL.contains(c) ? 1 : THREE_DECIMAL.contains(c) ? 1000 : 100;
        return amount.multiply(BigDecimal.valueOf(factor)).longValue();
    }

    // ── Payment intents ─────────────────────────────────────────────────────

    /** A created PaymentIntent: its id plus the client secret the browser needs. */
    public record IntentResult(String paymentIntentId, String clientSecret, String status) {}

    /**
     * Create a PaymentIntent as a DIRECT charge on the venue's connected account.
     *
     * @param connectedAccountId the venue's {@code acct_…}; required — without it the
     *                           charge would land on the platform account, in the
     *                           platform's country, and local rails would disappear
     * @param methodTypes        rails to enable, already resolved from the venue country
     * @param idempotencyKey     stable per booking attempt, so a retried/timed-out
     *                           create returns the SAME intent instead of double-charging
     */
    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "stripe", fallbackMethod = "createPaymentIntentFallback")
    public IntentResult createPaymentIntent(BigDecimal amount, String currency,
                                            String connectedAccountId, List<String> methodTypes,
                                            String bookingRef, String idempotencyKey) {
        requireConfigured();
        if (connectedAccountId == null || connectedAccountId.isBlank()) {
            throw new BusinessException(
                "This venue is not connected to Stripe yet — payouts cannot be routed. "
                    + "Ask the venue owner to finish payment onboarding.",
                HttpStatus.CONFLICT);
        }
        long subunits = toSubunits(amount, currency);

        MultiValueMap<String, String> form = form();
        form.add("amount", String.valueOf(subunits));
        form.add("currency", currency.toLowerCase());
        if (bookingRef != null && !bookingRef.isBlank()) {
            // Metadata is how a webhook maps a Stripe object back to our booking.
            form.add("metadata[bookingRef]", bookingRef);
            form.add("description", "Booking " + bookingRef);
        }
        if (methodTypes != null && !methodTypes.isEmpty()) {
            for (int i = 0; i < methodTypes.size(); i++) {
                form.add("payment_method_types[" + i + "]", methodTypes.get(i));
            }
        } else {
            form.add("automatic_payment_methods[enabled]", "true");
        }
        long fee = applicationFeeAmount(subunits);
        if (fee > 0) {
            form.add("application_fee_amount", String.valueOf(fee));
        }

        Map<String, Object> res = post("/payment_intents", form, connectedAccountId, idempotencyKey);
        if (res == null || res.get("id") == null) {
            throw new BusinessException("Stripe PaymentIntent creation failed — no id returned",
                HttpStatus.BAD_GATEWAY);
        }
        return new IntentResult(
            String.valueOf(res.get("id")),
            res.get("client_secret") == null ? null : String.valueOf(res.get("client_secret")),
            res.get("status") == null ? null : String.valueOf(res.get("status")));
    }

    @SuppressWarnings("unused")
    private IntentResult createPaymentIntentFallback(BigDecimal amount, String currency,
                                                     String connectedAccountId, List<String> methodTypes,
                                                     String bookingRef, String idempotencyKey, Throwable t) {
        log.error("Circuit breaker OPEN for Stripe — intent creation failed for booking={}: {}",
            bookingRef, t.getMessage());
        throw new BusinessException(
            "Payment gateway is temporarily unavailable. Please try again later.",
            HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Platform commission for a charge, in the currency's smallest unit. */
    long applicationFeeAmount(long subunits) {
        if (applicationFeeBps <= 0) return 0;
        return Math.floorDiv(subunits * applicationFeeBps, 10_000L);
    }

    /** Authoritative status of an intent, or null when it cannot be determined. */
    @SuppressWarnings("unchecked")
    public String fetchIntentStatus(String paymentIntentId, String connectedAccountId) {
        if (!isConfigured()) return null;
        try {
            Map<String, Object> res = get("/payment_intents/" + paymentIntentId, connectedAccountId);
            return res == null || res.get("status") == null ? null : String.valueOf(res.get("status"));
        } catch (Exception e) {
            log.warn("Stripe intent-status lookup failed for {}: {}", paymentIntentId, e.getMessage());
            return null;
        }
    }

    // ── Refunds ─────────────────────────────────────────────────────────────

    /**
     * Refund against a PaymentIntent on the connected account.
     *
     * <p>Deliberately NOT retried: a timeout after Stripe accepted the refund
     * followed by a blind retry would refund the customer twice. The caller
     * records a FAILED refund for the admin queue instead — same fail-closed rule
     * as {@link RazorpayGatewayClient#createRefund}. The idempotency key makes a
     * *deliberate* retry safe.
     */
    /**
     * Outcome of a Stripe refund. Mirrors the Razorpay client's result shape but is
     * its own type — a gateway client should not depend on a sibling gateway's
     * classes. {@code status} is normalised to the shared vocabulary
     * ("processed"/"pending"/"failed") so downstream handling stays provider-agnostic.
     */
    public record StripeRefundResult(String refundId, String status) {
        public boolean processed() { return "processed".equalsIgnoreCase(status); }
        public boolean failed()    { return "failed".equalsIgnoreCase(status); }
        public boolean pending()   { return !processed() && !failed(); }
    }

    @SuppressWarnings("unchecked")
    public StripeRefundResult createRefund(
            String paymentIntentId, BigDecimal amount, String currency,
            String connectedAccountId, String internalRef) {
        requireConfigured();
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new BusinessException(
                "Cannot refund via Stripe — payment has no intent id", HttpStatus.CONFLICT);
        }
        MultiValueMap<String, String> form = form();
        form.add("payment_intent", paymentIntentId);
        form.add("amount", String.valueOf(toSubunits(amount, currency)));
        if (internalRef != null && !internalRef.isBlank()) {
            form.add("metadata[internal_ref]", internalRef);
        }
        // Refunding a direct charge also claws back the platform fee.
        if (applicationFeeBps > 0) {
            form.add("refund_application_fee", "true");
        }

        Map<String, Object> res = post("/refunds", form, connectedAccountId, internalRef);
        if (res == null || res.get("id") == null) {
            throw new BusinessException("Stripe refund creation failed — no refund id returned",
                HttpStatus.BAD_GATEWAY);
        }
        String status = res.get("status") == null ? "pending" : String.valueOf(res.get("status"));
        // Stripe reports "succeeded"; the shared vocabulary is "processed".
        String normalised = "succeeded".equalsIgnoreCase(status) ? "processed" : status;
        return new StripeRefundResult(String.valueOf(res.get("id")), normalised);
    }

    // ── Connect: onboarding a venue as a connected account ──────────────────

    /** A connected account's onboarding/capability state. */
    public record AccountState(String accountId, boolean chargesEnabled,
                               boolean payoutsEnabled, boolean detailsSubmitted) {}

    /**
     * Create an Express connected account for a venue.
     *
     * <p>{@code country} is the VENUE's country and is immutable at Stripe once
     * set — it determines the account's settlement currency, its available local
     * payment methods, and its KYC requirements. Getting it wrong means the venue
     * must be re-onboarded from scratch.
     */
    @SuppressWarnings("unchecked")
    public String createConnectedAccount(String countryIso2, String email, Long bingeId) {
        requireConfigured();
        MultiValueMap<String, String> form = form();
        form.add("type", "express");
        form.add("country", countryIso2.toUpperCase());
        if (email != null && !email.isBlank()) form.add("email", email);
        form.add("capabilities[card_payments][requested]", "true");
        form.add("capabilities[transfers][requested]", "true");
        if (bingeId != null) form.add("metadata[bingeId]", String.valueOf(bingeId));

        // Idempotent on the venue so a double-click cannot create two accounts —
        // duplicate connected accounts are painful to unwind (each needs its own KYC).
        Map<String, Object> res = post("/accounts", form, null,
            bingeId == null ? null : "connect-acct-binge-" + bingeId);
        if (res == null || res.get("id") == null) {
            throw new BusinessException("Stripe account creation failed", HttpStatus.BAD_GATEWAY);
        }
        return String.valueOf(res.get("id"));
    }

    /**
     * A single-use onboarding URL the venue owner completes KYC at. These expire
     * quickly, so generate one per click rather than storing it.
     */
    @SuppressWarnings("unchecked")
    public String createAccountLink(String accountId, String refreshUrl, String returnUrl) {
        requireConfigured();
        MultiValueMap<String, String> form = form();
        form.add("account", accountId);
        form.add("refresh_url", refreshUrl);
        form.add("return_url", returnUrl);
        form.add("type", "account_onboarding");

        Map<String, Object> res = post("/account_links", form, null, null);
        if (res == null || res.get("url") == null) {
            throw new BusinessException("Stripe onboarding link creation failed", HttpStatus.BAD_GATEWAY);
        }
        return String.valueOf(res.get("url"));
    }

    /** Current capability state, used to decide whether a venue can actually be charged. */
    @SuppressWarnings("unchecked")
    public AccountState fetchAccount(String accountId) {
        requireConfigured();
        Map<String, Object> res = get("/accounts/" + accountId, null);
        if (res == null) {
            throw new BusinessException("Stripe account lookup failed", HttpStatus.BAD_GATEWAY);
        }
        return new AccountState(
            accountId,
            Boolean.TRUE.equals(res.get("charges_enabled")),
            Boolean.TRUE.equals(res.get("payouts_enabled")),
            Boolean.TRUE.equals(res.get("details_submitted")));
    }

    // ── Webhooks ────────────────────────────────────────────────────────────

    /**
     * Verify a {@code Stripe-Signature} header against the raw request body.
     *
     * <p>Stripe signs {@code "{timestamp}.{body}"} with HMAC-SHA256 under the
     * endpoint secret. The body must be the EXACT bytes received — re-serialising
     * parsed JSON changes whitespace/ordering and breaks verification.
     *
     * <p>The timestamp is checked against a tolerance window so a captured
     * webhook cannot be replayed indefinitely.
     */
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader, long toleranceSeconds) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("Stripe webhook received but app.stripe.webhook-secret is not configured — rejecting");
            return false;
        }
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) return false;
        try {
            String timestamp = null;
            java.util.List<String> signatures = new java.util.ArrayList<>();
            for (String part : signatureHeader.split(",")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0])) timestamp = kv[1];
                else if ("v1".equals(kv[0])) signatures.add(kv[1]);
            }
            if (timestamp == null || signatures.isEmpty()) return false;

            long ts = Long.parseLong(timestamp);
            long ageSeconds = Math.abs((System.currentTimeMillis() / 1000L) - ts);
            if (toleranceSeconds > 0 && ageSeconds > toleranceSeconds) {
                log.warn("Stripe webhook rejected — timestamp {}s outside tolerance", ageSeconds);
                return false;
            }

            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            byte[] expected = sb.toString().getBytes(StandardCharsets.UTF_8);

            // Constant-time compare against every provided v1 signature (Stripe sends
            // more than one during endpoint-secret rotation).
            for (String candidate : signatures) {
                if (java.security.MessageDigest.isEqual(
                        expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Stripe webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }

    // ── HTTP plumbing ───────────────────────────────────────────────────────

    private MultiValueMap<String, String> form() {
        return new LinkedMultiValueMap<>();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException(
                "Stripe is not configured on this environment (app.stripe.secret-key is empty).",
                HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    // Headers are applied through the Consumer form rather than chained .header()
    // calls: the fluent spec types are self-referentially generic, so conditionally
    // reassigning them does not type-check cleanly.

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, MultiValueMap<String, String> form,
                                     String connectedAccountId, String idempotencyKey) {
        try {
            return restClient.post()
                .uri(API + path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(h -> {
                    h.set("Authorization", "Bearer " + secretKey);
                    if (connectedAccountId != null && !connectedAccountId.isBlank()) {
                        h.set("Stripe-Account", connectedAccountId);
                    }
                    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        h.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .body(form)
                .retrieve()
                .body(Map.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw translate(e, path);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, String connectedAccountId) {
        try {
            return restClient.get()
                .uri(API + path)
                .headers(h -> {
                    h.set("Authorization", "Bearer " + secretKey);
                    if (connectedAccountId != null && !connectedAccountId.isBlank()) {
                        h.set("Stripe-Account", connectedAccountId);
                    }
                })
                .retrieve()
                .body(Map.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw translate(e, path);
        }
    }

    /**
     * Surface Stripe's own error message rather than a bare 502 — its messages
     * ("Your card was declined", "capability not active") are the actionable part
     * for both the customer and the support agent reading the log.
     */
    @SuppressWarnings("unchecked")
    private BusinessException translate(org.springframework.web.client.RestClientResponseException e, String path) {
        String detail = null;
        try {
            Map<String, Object> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("error") instanceof Map<?, ?> err) {
                Object msg = err.get("message");
                if (msg != null) detail = String.valueOf(msg);
            }
        } catch (Exception ignored) {
            // Non-JSON error body — fall through to the generic message.
        }
        log.error("Stripe {} failed ({}): {}", path, e.getStatusCode(),
            detail != null ? detail : e.getMessage());
        boolean clientError = e.getStatusCode().is4xxClientError();
        return new BusinessException(
            detail != null ? detail : "Stripe request failed",
            clientError ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY);
    }
}
