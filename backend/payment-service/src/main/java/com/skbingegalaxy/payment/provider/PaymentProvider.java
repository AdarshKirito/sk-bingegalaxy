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

    CreateOrderResponse createOrder(CreateOrderRequest req);

    CallbackVerificationResult verifyCallback(Map<String, String> params);

    RefundResponse refund(RefundRequest req);

    // ── Value objects ──────────────────────────────────────────────────

    record CreateOrderRequest(
        String bookingRef,
        BigDecimal amount,
        String currency,
        String customerEmail,
        String customerName,
        String fxLockId,
        Map<String, String> metadata) {}

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
