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

    /**
     * Razorpay's domestic Indian rails (UPI / netbanking / wallets) are only
     * available when the venue — and therefore the settlement account — is in
     * India. For a venue anywhere else it runs as an international card gateway,
     * so we must not advertise UPI to, say, a US venue: the order would be
     * created and then fail at checkout.
     */
    @Override
    public Set<com.skbingegalaxy.common.enums.PaymentMethod> supportedMethods(String countryIso2) {
        if (countryIso2 != null && "IN".equalsIgnoreCase(countryIso2.trim())) {
            return Set.of(
                com.skbingegalaxy.common.enums.PaymentMethod.UPI,
                com.skbingegalaxy.common.enums.PaymentMethod.CARD,
                com.skbingegalaxy.common.enums.PaymentMethod.BANK_TRANSFER,
                com.skbingegalaxy.common.enums.PaymentMethod.WALLET);
        }
        return Set.of(com.skbingegalaxy.common.enums.PaymentMethod.CARD);
    }

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
        if (params == null) {
            return new CallbackVerificationResult(false, null, null, "no-params");
        }
        String orderId = params.get("razorpay_order_id");
        String paymentId = params.get("razorpay_payment_id");
        String signature = params.get("razorpay_signature");
        // Cryptographic verification — HMAC-SHA256(order|payment) with the API
        // key secret. Field presence alone is NOT verification.
        boolean valid = orderId != null
            && razorpay.verifyCheckoutSignature(orderId, paymentId, signature);
        return new CallbackVerificationResult(
            valid, paymentId, orderId,
            valid ? "hmac-verified" : "hmac-verification-failed");
    }

    @Override
    public RefundResponse refund(RefundRequest req) {
        var result = razorpay.createRefund(
            req.gatewayPaymentId(), req.amount(), req.currency(),
            req.metadata() != null ? req.metadata().get("internal_ref") : null);
        return new RefundResponse(name(), result.refundId(), req.amount(), req.currency(), result.status());
    }
}
