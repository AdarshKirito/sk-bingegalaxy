package com.skbingegalaxy.payment.method;

import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.payment.provider.PaymentProvider;
import com.skbingegalaxy.payment.provider.PaymentProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which payment methods a customer may use for a booking, from the
 * VENUE's country — never the customer's.
 *
 * <p>Resolution is an intersection of two independent facts:
 * <ol>
 *   <li><b>Demand</b> — {@link PaymentMethodCatalog}: the rails a customer in
 *       that market expects, in local preference order.</li>
 *   <li><b>Supply</b> — {@link PaymentProvider#supportedMethods(String)} of the
 *       gateway that can actually settle the venue's currency.</li>
 * </ol>
 * Intersecting the two is what prevents the two failure modes we care about:
 * offering UPI to a US venue (gateway would reject it), and offering nothing at
 * all because a market's preferred rail happens to be unsupported.
 *
 * <p>The provider is chosen by {@link PaymentProviderRegistry#resolveForCurrency}
 * so that when a second gateway (Stripe/Connect) is registered, venues whose
 * currency Razorpay cannot settle automatically route to it and their method
 * list widens — with no change to this class.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentMethodResolver {

    private final PaymentProviderRegistry providerRegistry;

    /**
     * The ordered, offerable rails for a venue in {@code venueCountry} charging
     * in {@code currency}.
     *
     * @param venueCountry ISO-3166 alpha-2 of the VENUE (null on legacy venues)
     * @param currency     the venue's ISO-4217 settlement currency
     * @return non-empty ordered list; card-only as the last-resort floor
     */
    public Resolution resolve(String venueCountry, String currency) {
        return resolve(venueCountry, currency, null);
    }

    /**
     * As {@link #resolve(String, String)}, but pinning a preferred gateway.
     *
     * <p>Callers pass {@code "stripe"} when the venue has a chargeable Connect
     * account. Without that, the default gateway wins for any currency it can
     * settle (INR, USD, …) and the venue's connected account would never be used —
     * onboarding a venue to Connect would be a no-op, and its money would settle
     * into the platform's account instead of the venue's own bank.
     *
     * <p>The SAME preference must be passed everywhere this is called (display,
     * enforcement, charge), or the rails shown to the customer would come from one
     * gateway while the charge is created on another.
     */
    public Resolution resolve(String venueCountry, String currency, String preferredProvider) {
        PaymentProvider provider;
        try {
            provider = providerRegistry.resolveForCurrency(preferredProvider, currency);
        } catch (RuntimeException ex) {
            // No registered gateway settles this currency. That is a venue
            // mis-configuration (or a gateway we have not added yet), not a
            // customer error — surface it as an empty resolution so callers can
            // fail with a clear message instead of a gateway 4xx at checkout.
            log.error("No payment provider supports currency {} (venue country {}): {}",
                currency, venueCountry, ex.getMessage());
            return new Resolution(List.of(), null, venueCountry, currency);
        }

        // Legacy venues may have no country. Infer one from the settlement
        // currency rather than dropping to the card-only default — otherwise an
        // existing Indian venue with an un-backfilled country would lose UPI.
        String effectiveCountry = venueCountry;
        if (effectiveCountry == null || effectiveCountry.isBlank()) {
            effectiveCountry = PaymentMethodCatalog.inferCountryFromCurrency(currency);
            log.debug("Venue has no country; inferred {} from currency {} for method resolution",
                effectiveCountry, currency);
        }

        Set<PaymentMethod> gatewayCan = provider.supportedMethods(effectiveCountry);
        List<PaymentMethod> expected = PaymentMethodCatalog.forCountry(effectiveCountry);

        // Preserve the catalogue's local-preference ordering; the first surviving
        // entry becomes the UI default.
        List<PaymentMethod> offerable = new ArrayList<>();
        for (PaymentMethod m : expected) {
            if (m != PaymentMethod.CASH && gatewayCan.contains(m)) offerable.add(m);
        }

        if (offerable.isEmpty()) {
            // The market's expected rails and the gateway's capabilities do not
            // overlap at all. Fall back to any non-cash rail the gateway does
            // support so the customer is never left with zero ways to pay.
            // Sorted by enum order so the pre-selected rail is stable across JVMs —
            // Set iteration order is unspecified and this decides what a customer
            // sees selected by default.
            gatewayCan.stream()
                .filter(m -> m != PaymentMethod.CASH)
                .sorted()
                .forEach(offerable::add);
            if (!offerable.isEmpty()) {
                log.warn("Catalogue/gateway mismatch for country={} currency={} provider={} — "
                    + "falling back to gateway-supported rails {}",
                    venueCountry, currency, provider.name(), offerable);
            }
        }

        // Report the EFFECTIVE country so callers label the rails consistently with
        // how they were resolved (e.g. "Net banking" for an inferred Indian venue).
        return new Resolution(List.copyOf(offerable), provider.name(), effectiveCountry, currency);
    }

    /**
     * True when {@code method} may be charged for this venue. Used to enforce the
     * same rule server-side that the UI renders, so a hand-crafted request cannot
     * select a rail the venue does not offer.
     */
    public boolean isAllowed(String venueCountry, String currency, PaymentMethod method) {
        return method != null && resolve(venueCountry, currency).methods().contains(method);
    }

    /**
     * Outcome of resolution.
     *
     * @param methods  ordered offerable rails; empty means no gateway can serve
     *                 this venue's currency at all
     * @param provider the gateway that will handle the charge (null when none)
     */
    public record Resolution(List<PaymentMethod> methods, String provider,
                             String venueCountry, String currency) {

        /** The rail a customer gets pre-selected — the market's default. */
        public PaymentMethod defaultMethod() {
            return methods.isEmpty() ? null : methods.get(0);
        }
    }
}
