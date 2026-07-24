package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.payment.client.StripeGatewayClient;
import com.skbingegalaxy.payment.entity.PaymentConnectedAccount;
import com.skbingegalaxy.payment.repository.PaymentConnectedAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Owns the lifecycle of a venue's Stripe Connect account: create it, drive the
 * owner through KYC, and keep its capability flags in sync.
 *
 * <p>The capability flags matter more than they look. A connected account exists
 * the moment it is created, but cannot take money until Stripe finishes KYC. Any
 * routing decision must therefore ask {@link PaymentConnectedAccount#isChargeable()}
 * rather than merely "is there a row" — otherwise a venue mid-onboarding would be
 * offered at checkout and fail on the final confirm.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectedAccountService {

    private final PaymentConnectedAccountRepository repository;
    private final StripeGatewayClient stripe;

    /** Where Stripe returns the owner after onboarding (the admin payments page). */
    @Value("${app.stripe.onboarding-return-url:http://localhost:5173/admin/binges}")
    private String onboardingReturnUrl;

    @Value("${app.stripe.onboarding-refresh-url:http://localhost:5173/admin/binges}")
    private String onboardingRefreshUrl;

    /** The venue's account, if onboarding has ever been started. */
    @Transactional(readOnly = true)
    public Optional<PaymentConnectedAccount> findForBinge(Long bingeId) {
        return bingeId == null ? Optional.empty() : repository.findByBingeId(bingeId);
    }

    /**
     * The account id to charge on for this venue, or empty when the venue cannot
     * currently take money (not onboarded, or onboarded but not yet chargeable).
     */
    @Transactional(readOnly = true)
    public Optional<String> chargeableAccountId(Long bingeId) {
        return findForBinge(bingeId)
            .filter(PaymentConnectedAccount::isChargeable)
            .map(PaymentConnectedAccount::getAccountId);
    }

    /**
     * Start or resume onboarding for a venue and return a fresh Stripe-hosted
     * onboarding URL.
     *
     * <p>Idempotent on the venue: an existing row is reused rather than creating a
     * second connected account. Duplicate accounts each carry their own KYC and
     * are painful to unwind, so this is guarded here AND by a UNIQUE constraint.
     *
     * @param venueCountry the VENUE's country — immutable at Stripe once the
     *                     account exists, since settlement currency, available
     *                     rails and KYC requirements all derive from it
     */
    @Transactional
    public OnboardingLink startOnboarding(Long bingeId, String venueCountry, String ownerEmail) {
        if (bingeId == null) {
            throw new BusinessException("A venue must be selected before connecting payments.",
                HttpStatus.BAD_REQUEST);
        }
        if (venueCountry == null || !venueCountry.trim().matches("^[A-Za-z]{2}$")) {
            throw new BusinessException(
                "This venue has no valid country set. Set the venue country before connecting payments — "
                    + "it determines the settlement currency and payment methods and cannot be changed afterwards.",
                HttpStatus.BAD_REQUEST);
        }
        String country = venueCountry.trim().toUpperCase();

        PaymentConnectedAccount account = repository.findByBingeId(bingeId).orElse(null);
        if (account == null) {
            String accountId = stripe.createConnectedAccount(country, ownerEmail, bingeId);
            account = repository.save(PaymentConnectedAccount.builder()
                .bingeId(bingeId)
                .provider("stripe")
                .accountId(accountId)
                .country(country)
                .build());
            log.info("Created Stripe connected account {} for binge {} ({})", accountId, bingeId, country);
        } else if (!account.getCountry().equalsIgnoreCase(country)) {
            // The venue's country was edited after onboarding. Stripe cannot move an
            // account between countries, so surface it instead of charging in the
            // wrong jurisdiction.
            throw new BusinessException(
                "This venue is connected to Stripe as a " + account.getCountry() + " account but its country "
                    + "is now " + country + ". A venue cannot change country after payment onboarding — "
                    + "contact support to migrate to a new connected account.",
                HttpStatus.CONFLICT);
        }

        String url = stripe.createAccountLink(
            account.getAccountId(), onboardingRefreshUrl, onboardingReturnUrl);
        return new OnboardingLink(account.getAccountId(), url, account.isChargeable());
    }

    /** Pull the latest capability flags from Stripe and persist them. */
    @Transactional
    public PaymentConnectedAccount refreshStatus(Long bingeId) {
        PaymentConnectedAccount account = repository.findByBingeId(bingeId)
            .orElseThrow(() -> new BusinessException(
                "This venue has not started payment onboarding yet.", HttpStatus.NOT_FOUND));
        var state = stripe.fetchAccount(account.getAccountId());
        return applyState(account, state);
    }

    /**
     * Apply capability flags pushed by an {@code account.updated} webhook. Keyed by
     * Stripe account id because that is what the webhook carries.
     */
    @Transactional
    public void applyWebhookState(String accountId, boolean chargesEnabled,
                                  boolean payoutsEnabled, boolean detailsSubmitted) {
        repository.findByAccountId(accountId).ifPresentOrElse(
            account -> applyState(account, new StripeGatewayClient.AccountState(
                accountId, chargesEnabled, payoutsEnabled, detailsSubmitted)),
            () -> log.warn("Stripe account.updated for unknown account {} — ignoring", accountId));
    }

    private PaymentConnectedAccount applyState(PaymentConnectedAccount account,
                                               StripeGatewayClient.AccountState state) {
        boolean became = !account.isChargesEnabled() && state.chargesEnabled();
        account.setChargesEnabled(state.chargesEnabled());
        account.setPayoutsEnabled(state.payoutsEnabled());
        account.setDetailsSubmitted(state.detailsSubmitted());
        PaymentConnectedAccount saved = repository.save(account);
        if (became) {
            log.info("Stripe account {} (binge {}) is now chargeable",
                account.getAccountId(), account.getBingeId());
        }
        return saved;
    }

    /**
     * @param accountId  the venue's Stripe account
     * @param url        single-use hosted onboarding URL (expires quickly)
     * @param chargeable whether the venue can already take money
     */
    public record OnboardingLink(String accountId, String url, boolean chargeable) {}
}
