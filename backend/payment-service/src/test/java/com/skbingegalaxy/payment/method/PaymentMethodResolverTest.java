package com.skbingegalaxy.payment.method;

import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.payment.provider.PaymentProvider;
import com.skbingegalaxy.payment.provider.PaymentProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The core promise: payment methods follow the VENUE's country, never the
 * customer's. These tests encode the two scenarios that motivated the feature —
 * a foreign customer at an Indian venue, and an Indian customer at a foreign one.
 */
@ExtendWith(MockitoExtension.class)
class PaymentMethodResolverTest {

    @Mock private PaymentProviderRegistry registry;
    @InjectMocks private PaymentMethodResolver resolver;

    /** Stand-in for Razorpay: domestic rails in India, cards elsewhere. */
    private static PaymentProvider gateway(Set<String> currencies,
                                           Map<String, Set<PaymentMethod>> methodsByCountry) {
        return new PaymentProvider() {
            @Override public String name() { return "razorpay"; }
            @Override public Set<String> supportedCurrencies() { return currencies; }
            @Override public Set<PaymentMethod> supportedMethods(String countryIso2) {
                return methodsByCountry.getOrDefault(
                    countryIso2 == null ? "" : countryIso2.toUpperCase(),
                    Set.of(PaymentMethod.CARD));
            }
            @Override public CreateOrderResponse createOrder(CreateOrderRequest req) {
                return new CreateOrderResponse(name(), "ord_1", BigDecimal.ONE, "INR", null, Map.of());
            }
            @Override public CallbackVerificationResult verifyCallback(Map<String, String> p) {
                return new CallbackVerificationResult(false, null, null, "n/a");
            }
            @Override public RefundResponse refund(RefundRequest req) {
                return new RefundResponse(name(), "rfnd_1", BigDecimal.ONE, "INR", "processed");
            }
        };
    }

    private static PaymentProvider razorpayLike() {
        return gateway(
            Set.of("INR", "USD", "EUR", "GBP"),
            Map.of("IN", Set.of(PaymentMethod.UPI, PaymentMethod.CARD,
                                PaymentMethod.BANK_TRANSFER, PaymentMethod.WALLET)));
    }

    @Test
    @DisplayName("An Indian venue offers UPI first — regardless of where the customer is")
    void indianVenueOffersUpiFirst() {
        when(registry.resolveForCurrency(any(), eq("INR"))).thenReturn(razorpayLike());

        var resolution = resolver.resolve("IN", "INR");

        assertThat(resolution.methods()).containsExactly(
            PaymentMethod.UPI, PaymentMethod.CARD,
            PaymentMethod.BANK_TRANSFER, PaymentMethod.WALLET);
        // UPI is the pre-selected rail for an Indian venue.
        assertThat(resolution.defaultMethod()).isEqualTo(PaymentMethod.UPI);
    }

    @Test
    @DisplayName("A US venue never offers UPI, even to a customer from India")
    void usVenueNeverOffersUpi() {
        when(registry.resolveForCurrency(any(), eq("USD"))).thenReturn(razorpayLike());

        var resolution = resolver.resolve("US", "USD");

        assertThat(resolution.methods()).doesNotContain(PaymentMethod.UPI);
        assertThat(resolution.defaultMethod()).isEqualTo(PaymentMethod.CARD);
    }

    @Test
    @DisplayName("CASH is never offered online — it is an offline settlement")
    void cashIsNeverOfferedOnline() {
        when(registry.resolveForCurrency(any(), eq("INR"))).thenReturn(gateway(
            Set.of("INR"), Map.of("IN", Set.of(PaymentMethod.CASH, PaymentMethod.UPI))));

        var resolution = resolver.resolve("IN", "INR");

        assertThat(resolution.methods()).doesNotContain(PaymentMethod.CASH);
        assertThat(resolution.methods()).containsExactly(PaymentMethod.UPI);
    }

    @Test
    @DisplayName("A legacy venue with no country infers it from currency, keeping UPI for INR")
    void legacyVenueInfersCountryFromCurrency() {
        when(registry.resolveForCurrency(any(), eq("INR"))).thenReturn(razorpayLike());

        // Regression guard: before inference this fell to the card-only default,
        // silently removing UPI from existing Indian venues whose country column
        // was never backfilled.
        var resolution = resolver.resolve(null, "INR");

        assertThat(resolution.methods()).contains(PaymentMethod.UPI);
        assertThat(resolution.defaultMethod()).isEqualTo(PaymentMethod.UPI);
        assertThat(resolution.venueCountry()).isEqualTo("IN");
    }

    @Test
    @DisplayName("No country and an unmappable currency still yields a usable card option")
    void unmappableCurrencyStillOffersCard() {
        // BRL has no entry in the currency→country map, so inference yields null
        // and the international default applies.
        when(registry.resolveForCurrency(any(), eq("BRL"))).thenReturn(
            gateway(Set.of("BRL"), Map.of()));

        var resolution = resolver.resolve(null, "BRL");

        assertThat(resolution.methods()).containsExactly(PaymentMethod.CARD);
    }

    @Test
    @DisplayName("A currency no gateway can settle resolves to no methods rather than a bad offer")
    void unsupportedCurrencyResolvesEmpty() {
        when(registry.resolveForCurrency(any(), eq("NGN")))
            .thenThrow(new PaymentProvider.UnsupportedCurrencyException("razorpay", "NGN"));

        var resolution = resolver.resolve("NG", "NGN");

        assertThat(resolution.methods()).isEmpty();
        assertThat(resolution.defaultMethod()).isNull();
    }

    @Test
    @DisplayName("isAllowed mirrors the offered list, so the guard and the UI cannot disagree")
    void isAllowedMirrorsOfferedList() {
        when(registry.resolveForCurrency(any(), eq("USD"))).thenReturn(razorpayLike());

        assertThat(resolver.isAllowed("US", "USD", PaymentMethod.CARD)).isTrue();
        assertThat(resolver.isAllowed("US", "USD", PaymentMethod.UPI)).isFalse();
    }

    @Test
    @DisplayName("Bank-transfer labels are market-appropriate")
    void bankTransferLabelsAreLocalised() {
        assertThat(PaymentMethodCatalog.labelFor(PaymentMethod.BANK_TRANSFER, "IN"))
            .isEqualTo("Net banking");
        assertThat(PaymentMethodCatalog.labelFor(PaymentMethod.BANK_TRANSFER, "US"))
            .isEqualTo("Bank transfer (ACH)");
        assertThat(PaymentMethodCatalog.labelFor(PaymentMethod.BANK_TRANSFER, "DE"))
            .isEqualTo("Bank transfer (SEPA)");
    }
}
