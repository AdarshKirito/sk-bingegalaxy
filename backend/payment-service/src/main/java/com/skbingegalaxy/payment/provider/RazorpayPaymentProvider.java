package com.skbingegalaxy.payment.provider;

import com.skbingegalaxy.payment.client.RazorpayGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Razorpay implementation of {@link PaymentProvider}. Today this is the
 * default and only provider; the abstraction lets us add Stripe / Adyen /
 * PayPal alongside it without touching {@code PaymentService}.
 *
 * <p>The actual HTTP work still lives in {@link RazorpayGatewayClient};
 * this class just adapts the gateway-specific signature into the common
 * {@link PaymentProvider} contract.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RazorpayPaymentProvider implements PaymentProvider {

    private static final Set<String> SUPPORTED = Set.of(
        // Razorpay International (subset — extend as needed).
        "INR", "USD", "EUR", "GBP", "AED", "SGD", "AUD", "CAD"
    );

    private final RazorpayGatewayClient razorpay;

    @Override
    public String name() { return "razorpay"; }

    @Override
    public Set<String> supportedCurrencies() { return SUPPORTED; }

    @Override
    public CreateOrderResponse createOrder(CreateOrderRequest req) {
        if (!supportsCurrency(req.currency())) {
            throw new UnsupportedCurrencyException(name(), req.currency());
        }
        String receipt = req.bookingRef() != null ? req.bookingRef()
            : "rcpt-" + UUID.randomUUID().toString().substring(0, 12);
        String orderId = razorpay.createOrder(req.amount(), req.currency().toUpperCase(), receipt);
        return new CreateOrderResponse(
            name(),
            orderId,
            req.amount(),
            req.currency().toUpperCase(),
            null,
            Map.of("razorpayOrderId", orderId)
        );
    }

    @Override
    public CallbackVerificationResult verifyCallback(Map<String, String> params) {
        // Existing webhook verification code lives in PaymentService /
        // RazorpayGatewayClient; we keep this stub minimal for the abstraction.
        // Production callers should keep using the existing dedicated path
        // until the migration to provider.verifyCallback() is complete.
        boolean valid = params != null
            && params.containsKey("razorpay_payment_id")
            && params.containsKey("razorpay_signature");
        return new CallbackVerificationResult(
            valid,
            params == null ? null : params.get("razorpay_payment_id"),
            params == null ? null : params.get("razorpay_order_id"),
            valid ? "signature-fields-present" : "missing-signature-fields"
        );
    }

    @Override
    public RefundResponse refund(RefundRequest req) {
        // Razorpay refund is currently driven from PaymentService directly.
        // Surface a clear "not yet wired" so callers using the abstraction
        // know to keep using the legacy path until refund is migrated here.
        log.warn("RazorpayPaymentProvider.refund() called but not yet implemented — "
            + "use PaymentService.refundPayment() until the migration completes");
        return new RefundResponse(name(), null, req.amount(), req.currency(), "NOT_IMPLEMENTED");
    }
}
