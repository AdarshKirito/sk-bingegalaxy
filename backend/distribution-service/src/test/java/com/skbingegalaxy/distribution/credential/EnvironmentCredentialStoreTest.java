package com.skbingegalaxy.distribution.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store exists to make one thing impossible: a provider secret in
 * {@code distribution_db}. These tests pin the behaviours that keep it impossible.
 */
@DisplayName("EnvironmentCredentialStore")
class EnvironmentCredentialStoreTest {

    private final MockEnvironment env = new MockEnvironment();
    private final EnvironmentCredentialStore store = new EnvironmentCredentialStore(env);

    @Test
    @DisplayName("a reference maps to a predictable environment variable")
    void referenceMapsToEnvKey() {
        // Predictable because an operator has to be able to provision it by hand from
        // the reference alone — the error message names this variable.
        assertThat(EnvironmentCredentialStore.toEnvKey("viator/binge-12/production"))
            .isEqualTo("DIST_CRED_VIATOR_BINGE_12_PRODUCTION");
    }

    @Test
    @DisplayName("resolves a provisioned secret")
    void resolvesProvisionedSecret() {
        env.setProperty("DIST_CRED_VIATOR_BINGE_1", "s3cr3t");
        assertThat(store.resolve("viator/binge-1")).contains("s3cr3t");
    }

    @Test
    @DisplayName("an unprovisioned reference resolves to empty, never to a blank secret")
    void unprovisionedResolvesEmpty() {
        // Callers must read this as "connection not usable". Returning "" instead would
        // read as "authenticated with an empty credential" somewhere downstream.
        assertThat(store.resolve("viator/nothing-here")).isEmpty();
    }

    @Test
    @DisplayName("blank and null references resolve to empty without touching the environment")
    void blankReferenceIsEmpty() {
        assertThat(store.resolve(null)).isEmpty();
        assertThat(store.resolve("   ")).isEmpty();
    }

    @Test
    @DisplayName("a provisioned-but-blank value counts as absent")
    void blankValueIsAbsent() {
        env.setProperty("DIST_CRED_X", "   ");
        assertThat(store.resolve("x")).isEmpty();
    }

    @Test
    @DisplayName("writes are refused, with an instruction the operator can act on")
    void writesAreRefused() {
        assertThat(store.supportsWrite()).isFalse();

        // Refusing beats encrypting into the database (which needs a key that is itself
        // not in the database, i.e. a secrets manager) and beats storing plaintext,
        // which is the incident this design exists to prevent.
        assertThatThrownBy(() -> store.store("viator/binge-1", "s3cr3t"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("DIST_CRED_VIATOR_BINGE_1")
            // The message must not echo the secret it just refused to store.
            .hasMessageNotContaining("s3cr3t");
    }
}
