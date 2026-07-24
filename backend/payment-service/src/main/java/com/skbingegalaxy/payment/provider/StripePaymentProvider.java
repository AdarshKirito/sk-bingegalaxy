package com.skbingegalaxy.payment.provider;

import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.payment.client.StripeGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stripe Connect implementation of {@link PaymentProvider}, using direct charges
 * on the venue's connected account.
 *
 * <p>This is what makes cross-border venue-local payment work: the PaymentIntent
 * is created on an account domiciled in the VENUE's country, so Stripe offers
 * that country's rails (UPI for an Indian venue) to a customer sitting anywhere.
 * A platform-account charge could never do this — it would only ever offer the
 * platform's own country's methods.
 *
 * <p>Stays dormant unless configured: {@link #supportedCurrencies()} returns empty
 * when no secret key is set, so {@link PaymentProviderRegistry#resolveForCurrency}
 * will never route to it on an environment without Stripe credentials.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StripePaymentProvider implements PaymentProvider {

    private final StripeGatewayClient stripe;

    /**
     * Stripe settlement currencies we support, aligned with the countries in
     * {@code PaymentMethodCatalog}. Broader than Razorpay's set — covering a
     * currency Razorpay cannot settle is precisely why this provider exists.
     */
    private static final Set<String> SUPPORTED = Set.of(
        "USD", "EUR", "GBP", "INR", "AUD", "CAD", "SGD", "AED", "SAR", "CHF",
        "JPY", "CNY", "HKD", "NZD", "SEK", "NOK", "DKK", "PLN", "MYR", "THB",
        "IDR", "PHP", "KRW", "BRL", "MXN", "ZAR", "TRY", "NGN", "KES"
    );

    @Override
    public String name() { return "stripe"; }

    @Override
    public Set<String> supportedCurrencies() {
        // Unconfigured Stripe must never win provider selection — an empty set
        // makes it invisible to resolveForCurrency instead of failing at charge time.
        return stripe.isConfigured() ? SUPPORTED : Set.of();
    }

    /**
     * Rails Stripe can enable for a venue in this country.
     *
     * <p>Deliberately conservative: sending Stripe a {@code payment_method_types}
     * value the account cannot use is a hard 400 that breaks checkout, so a rail
     * is only claimed where the mapping to a real Stripe type is certain.
     */
    @Override
    public Set<PaymentMethod> supportedMethods(String countryIso2) {
        String cc = countryIso2 == null ? "" : countryIso2.trim().toUpperCase(Locale.ROOT);
        return switch (cc) {
            // India: UPI and netbanking are the local rails Stripe India exposes.
            case "IN" -> Set.of(PaymentMethod.UPI, PaymentMethod.CARD, PaymentMethod.BANK_TRANSFER);
            // US: cards plus ACH debit.
            case "US" -> Set.of(PaymentMethod.CARD, PaymentMethod.BANK_TRANSFER);
            // SEPA countries: cards plus SEPA direct debit.
            case "DE", "FR", "IT", "ES", "NL", "IE", "PT", "BE", "AT", "GR", "FI", "LU" ->
                Set.of(PaymentMethod.CARD, PaymentMethod.BANK_TRANSFER);
            // Everywhere else Stripe reaches: cards are the dependable baseline.
            default -> Set.of(PaymentMethod.CARD);
        };
    }

    /**
     * Stripe's {@code payment_method_types} names for our rails, per country.
     * Returns only mappings we are confident about; anything unmappable is
     * dropped rather than guessed, and an empty result makes the client fall back
     * to {@code automatic_payment_methods}, letting Stripe choose.
     */
    static List<String> toStripeMethodTypes(List<PaymentMethod> methods, String countryIso2) {
        String cc = countryIso2 == null ? "" : countryIso2.trim().toUpperCase(Locale.ROOT);
        // LinkedHashMap keeps the resolver's preference order (first = default rail).
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        if (methods != null) {
            for (PaymentMethod m : methods) {
                String type = switch (m) {
                    case CARD -> "card";
                    case UPI -> "IN".equals(cc) ? "upi" : null;
                    case BANK_TRANSFER -> switch (cc) {
                        case "IN" -> "netbanking";
                        case "US" -> "us_bank_account";
                        case "DE", "FR", "IT", "ES", "NL", "IE", "PT", "BE", "AT", "GR", "FI", "LU" ->
                            "sepa_debit";
                        default -> null;
                    };
                    // Wallet rails differ per market and several need extra Stripe
                    // activation; omitted rather than risk a 400 at checkout.
                    case WALLET, CASH -> null;
                };
                if (type != null) ordered.put(type, Boolean.TRUE);
            }
        }
        return new ArrayList<>(ordered.keySet());
    }

    @Override
    public CreateOrderResponse createOrder(CreateOrderRequest req) {
        if (!supportsCurrency(req.currency())) {
            throw new UnsupportedCurrencyException(name(), req.currency());
        }
        List<String> methodTypes = toStripeMethodTypes(req.paymentMethods(), req.venueCountry());

        // Idempotency is keyed on the PAYMENT ATTEMPT, not the booking. Keying it
        // on bookingRef looked safer but was wrong: a second, legitimate attempt
        // for the same booking at a different amount (partial payment, balance
        // changed after a refund) would reuse the key, and Stripe rejects a reused
        // key carrying different parameters — checkout would fail outright. The
        // transaction id is unique per payment row, so a retry of the SAME attempt
        // still de-duplicates while a genuinely new attempt gets its own key.
        String attemptId = req.metadata() == null ? null : req.metadata().get("transactionId");
        String idempotencyKey = attemptId != null && !attemptId.isBlank()
            ? "intent-" + attemptId
            : (req.bookingRef() == null ? null : "intent-" + req.bookingRef());

        StripeGatewayClient.IntentResult intent = stripe.createPaymentIntent(
            req.amount(), req.currency(), req.connectedAccountId(),
            methodTypes, req.bookingRef(), idempotencyKey);

        Map<String, String> checkoutFields = new LinkedHashMap<>();
        if (intent.clientSecret() != null) {
            // The browser confirms the intent with this; it is scoped to this one
            // intent and is safe to hand to the client.
            checkoutFields.put("stripeClientSecret", intent.clientSecret());
        }
        if (req.connectedAccountId() != null) {
            // Stripe.js must be initialised with the connected account for a
            // direct charge, otherwise confirmation targets the platform account.
            checkoutFields.put("stripeAccountId", req.connectedAccountId());
        }
        checkoutFields.put("stripePaymentIntentId", intent.paymentIntentId());

        return new CreateOrderResponse(
            name(),
            intent.paymentIntentId(),
            req.amount(),
            req.currency().toUpperCase(Locale.ROOT),
            null,
            checkoutFields);
    }

    /**
     * Stripe confirms payment through webhooks and the intent's own status rather
     * than a signed redirect payload, so callback "verification" here is a status
     * read against the authoritative object.
     */
    @Override
    public CallbackVerificationResult verifyCallback(Map<String, String> params) {
        if (params == null) {
            return new CallbackVerificationResult(false, null, null, "no-params");
        }
        String intentId = params.get("payment_intent");
        String account = params.get("stripe_account");
        if (intentId == null || intentId.isBlank()) {
            return new CallbackVerificationResult(false, null, null, "missing-payment-intent");
        }
        String status = stripe.fetchIntentStatus(intentId, account);
        boolean succeeded = "succeeded".equalsIgnoreCase(status);
        return new CallbackVerificationResult(
            succeeded, intentId, intentId,
            succeeded ? "intent-succeeded" : "intent-status-" + status);
    }

    @Override
    public RefundResponse refund(RefundRequest req) {
        String account = req.metadata() == null ? null : req.metadata().get("stripe_account");
        String internalRef = req.metadata() == null ? null : req.metadata().get("internal_ref");
        var result = stripe.createRefund(
            req.gatewayPaymentId(), req.amount(), req.currency(), account, internalRef);
        return new RefundResponse(
            name(), result.refundId(), req.amount(), req.currency(), result.status());
    }
}
