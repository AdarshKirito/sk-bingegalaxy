package com.skbingegalaxy.distribution.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves provider secrets from the process environment. <b>Read-only by design.</b>
 *
 * <p>A reference like {@code viator/binge-12/production} resolves to the environment
 * variable {@code DIST_CRED_VIATOR_BINGE_12_PRODUCTION}, which is how the secret reaches
 * a container today: Docker Compose env, or a Kubernetes secret mounted as env.
 *
 * <p><b>Why writes are refused rather than implemented.</b> The alternatives were to
 * encrypt secrets into {@code distribution_db} — which needs key management, key
 * rotation and a key that is itself not in the database, i.e. a secrets manager — or to
 * store them in plaintext, which is the incident this design exists to prevent. Refusing
 * is honest: an operator provisions the secret the same way every other secret in this
 * stack is provisioned, and the API tells them exactly that instead of silently
 * accepting a value it cannot protect.
 *
 * <p>Swapping in AWS Secrets Manager or GCP Secret Manager means one new
 * {@link CredentialStore} bean; nothing else in the service changes. That is the point
 * of the port.
 */
@Component
@Slf4j
public class EnvironmentCredentialStore implements CredentialStore {

    private static final String PREFIX = "DIST_CRED_";

    private final Environment environment;

    public EnvironmentCredentialStore(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) return Optional.empty();
        String key = toEnvKey(credentialRef);
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            // The REFERENCE is logged, never the value, and never a guess at what the
            // value might be. An operator needs to know which variable to set.
            log.warn("No credential provisioned for ref '{}' (expected environment variable {})",
                credentialRef, key);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    @Override
    public boolean supportsWrite() {
        return false;
    }

    @Override
    public String store(String credentialRef, String secret) {
        throw new UnsupportedOperationException(
            "This deployment resolves provider secrets from the environment and cannot "
          + "store them. Provision " + toEnvKey(credentialRef) + " on distribution-service, "
          + "then create the connection with that reference.");
    }

    /** {@code viator/binge-12/production} → {@code DIST_CRED_VIATOR_BINGE_12_PRODUCTION}. */
    static String toEnvKey(String credentialRef) {
        String normalised = credentialRef == null ? "" : credentialRef.trim();
        StringBuilder sb = new StringBuilder(PREFIX);
        for (char c : normalised.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c)
                ? Character.toUpperCase(c)
                : '_');
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }
}
