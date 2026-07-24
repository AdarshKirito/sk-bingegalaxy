package com.skbingegalaxy.notification.config;

import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.GeneralSecurityException;
import java.security.Security;

/**
 * Wires the Web Push (VAPID) sender.
 *
 * <p>A {@link PushService} bean is created only when a VAPID key pair is configured
 * ({@code app.webpush.public-key} / {@code private-key} non-blank). When absent — e.g.
 * a deployment that hasn't generated keys — the bean is {@code null} and
 * {@code WebPushService} degrades to a no-op, so the rest of notification delivery is
 * unaffected (fail-open, zero blast radius).
 *
 * <p>The public key is safe to expose to browsers (it is the {@code applicationServerKey}
 * they subscribe with); the private key is a secret and MUST be overridden per environment
 * via {@code WEBPUSH_PRIVATE_KEY}. Rotating the PUBLIC key invalidates every existing
 * subscription, so it must stay stable for the life of a deployment.
 */
@Slf4j
@Configuration
public class WebPushConfig {

    @Value("${app.webpush.public-key:}")
    private String publicKey;

    @Value("${app.webpush.private-key:}")
    private String privateKey;

    /** VAPID "sub" — a mailto: or https: contact the push service can reach us at. */
    @Value("${app.webpush.subject:mailto:noreply@skbingegalaxy.com}")
    private String subject;

    @Bean
    public PushService vapidPushService() throws GeneralSecurityException {
        if (publicKey == null || publicKey.isBlank() || privateKey == null || privateKey.isBlank()) {
            log.warn("Web Push disabled — app.webpush.public-key/private-key not configured. "
                + "Browser push notifications will be skipped (email/in-app unaffected).");
            return null;
        }
        // web-push relies on BouncyCastle for the ECDH / HKDF primitives.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        PushService svc = new PushService(publicKey, privateKey, subject);
        log.info("Web Push (VAPID) enabled — subject={}", subject);
        return svc;
    }
}
