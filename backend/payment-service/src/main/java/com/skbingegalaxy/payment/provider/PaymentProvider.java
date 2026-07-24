package com.skbingegalaxy.payment.provider;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Strategy interface that abstracts a payment gateway (Razorpay, Stripe,
 * Adyen, PayPal …) behind a single contract. The {@code PaymentService}
 * dispatches to the right implementation via {@link PaymentProviderRegistry}
 * keyed on {@link #name()}.
 *
 * <p>Each provider is responsible for:
 * <ul>
 *   <li>declaring which currencies and minor units it supports
 *       ({@link #supportedCurrencies()})</li>
 *   <li>creating an order/intent with the gateway
 *       ({@link #createOrder(CreateOrderRequest)})</li>
 *   <li>verifying webhook / signature payloads
 *       ({@link #verifyCallback(Map)})</li>
 *   <li>issuing refunds ({@link #refund(RefundRequest)})</li>
 * </ul>
 *
 * Implementations should throw {@link UnsupportedCurrencyException} when
 * asked to charge a currency outside {@link #supportedCurrencies()} so the
 * caller can fall back to a different provider or surface a clear error.
 */
public interface PaymentProvider {

    /** Stable id used to look up the provider, e.g. "razorpay". */
    String name();

    /** ISO-4217 currencies the gateway can charge. */
    Set<String> supportedCurrencies();

    /** Convenience check used by validation paths. */
    default boolean supportsCurrency(String iso) {
        return iso != null && supportedCurrencies().contains(iso.toUpperCase());
    }

    /**
     * Rails this gateway can actually charge for a venue in {@code countryIso2}.
     *
     * <p>This is the SUPPLY side of payment-method resolution: it is intersected
     * with what the market expects (the country catalogue) so we never render a
     * method the gateway would reject at checkout. Gateway capability is
     * genuinely country-dependent — Razorpay can take UPI for an Indian venue but
     * only cards for an international one — hence the parameter.
     *
     * <p>Defaults to card-only, the near-universal baseline, so a newly added
     * provider is conservative until it declares more.
     */
    default Set<com.skbingegalaxy.common.enums.PaymentMethod> supportedMethods(String countryIso2) {
        return Set.of(com.skbingegalaxy.common.enums.PaymentMethod.CARD);
    }

    CreateOrderResponse createOrder(CreateOrderRequest req);

    CallbackVerificationResult verifyCallback(Map<String, String> params);

    RefundResponse refund(RefundRequest req);

    // ── Value objects ──────────────────────────────────────────────────

    /**
     * @param venueCountry       ISO-3166 alpha-2 of the VENUE. Decides which rails the
     *                           gateway may enable for this charge.
     * @param connectedAccountId the venue's gateway account for marketplace providers
     *                           (Stripe Connect {@code acct_…}); null for providers that
     *                           settle to a single platform account, such as Razorpay.
     * @param paymentMethods     rails to enable, already resolved from the venue country;
     *                           empty lets the gateway decide.
     */
    record CreateOrderRequest(
        String bookingRef,
        BigDecimal amount,
        String currency,
        String customerEmail,
        String customerName,
        String fxLockId,
        Map<String, String> metadata,
        String venueCountry,
        String connectedAccountId,
        java.util.List<com.skbingegalaxy.common.enums.PaymentMethod> paymentMethods) {

        /** Convenience for providers that ignore venue routing (single-account gateways). */
        public CreateOrderRequest(String bookingRef, BigDecimal amount, String currency,
                                  String customerEmail, String customerName, String fxLockId,
                                  Map<String, String> metadata) {
            this(bookingRef, amount, currency, customerEmail, customerName, fxLockId, metadata,
                null, null, java.util.List.of());
        }
    }

    record CreateOrderResponse(
        String providerName,
        String gatewayOrderId,
        BigDecimal amount,
        String currency,
        String redirectUrl,
        Map<String, String> publicCheckoutFields) {}

    record CallbackVerificationResult(
        boolean valid,
        String gatewayPaymentId,
        String gatewayOrderId,
        String signatureSummary) {}

    record RefundRequest(
        String gatewayPaymentId,
        BigDecimal amount,
        String currency,
        String reason,
        Map<String, String> metadata) {}

    record RefundResponse(
        String providerName,
        String gatewayRefundId,
        BigDecimal amount,
        String currency,
        String status) {}

    /** Thrown when the requested currency is not supported by this gateway. */
    class UnsupportedCurrencyException extends RuntimeException {
        public UnsupportedCurrencyException(String provider, String currency) {
            super("Provider '" + provider + "' does not support currency '" + currency + "'");
        }
    }
}
