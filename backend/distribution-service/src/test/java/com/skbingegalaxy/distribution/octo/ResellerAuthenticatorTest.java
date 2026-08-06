package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Inbound reseller authentication — the credential direction that had no code until the
 * OCTO seam existed. Everything else in this service handles secrets SK Binge presents
 * TO a provider; here a reseller presents one to us.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OCTO reseller authentication")
class ResellerAuthenticatorTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private CredentialStore credentialStore;
    @InjectMocks private ResellerAuthenticator authenticator;

    private static Connection active(String ref) {
        return Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
            .status(Connection.ConnectionStatus.ACTIVE).credentialRef(ref).build();
    }

    @Test
    @DisplayName("a valid token resolves to its connection")
    void validTokenResolves() {
        when(connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE))
            .thenReturn(List.of(active("reseller/viator")));
        when(credentialStore.resolve("reseller/viator")).thenReturn(Optional.of("s3cr3t"));

        assertThat(authenticator.authenticate("Bearer s3cr3t"))
            .map(Connection::getId).contains(5L);
    }

    @Test
    @DisplayName("only ACTIVE connections authenticate")
    void onlyActiveConnectionsAreScanned() {
        // A paused or revoked connection must not authenticate anything — pausing is how
        // an operator stops a channel, and a token that still works would ignore them.
        when(connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE))
            .thenReturn(List.of());

        assertThat(authenticator.authenticate("Bearer s3cr3t")).isEmpty();
        verifyNoInteractions(credentialStore);
    }

    @Test
    @DisplayName("a wrong token is refused")
    void wrongTokenRefused() {
        when(connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE))
            .thenReturn(List.of(active("reseller/viator")));
        when(credentialStore.resolve("reseller/viator")).thenReturn(Optional.of("s3cr3t"));

        assertThat(authenticator.authenticate("Bearer wrong")).isEmpty();
    }

    @Test
    @DisplayName("a connection whose secret is unprovisioned cannot authenticate anyone")
    void unprovisionedSecretAuthenticatesNobody() {
        when(connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE))
            .thenReturn(List.of(active("reseller/viator")));
        when(credentialStore.resolve("reseller/viator")).thenReturn(Optional.empty());

        // Empty must read as "cannot authenticate", never as "no credential required".
        assertThat(authenticator.authenticate("Bearer anything")).isEmpty();
    }

    @Test
    @DisplayName("malformed and missing Authorization headers are refused without a scan")
    void malformedHeadersRefused() {
        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("")).isEmpty();
        assertThat(authenticator.authenticate("Basic abc")).isEmpty();
        assertThat(authenticator.authenticate("Bearer ")).isEmpty();
        verifyNoInteractions(connectionRepository);
    }

    @Test
    @DisplayName("the Bearer prefix is case-insensitive, the token is not")
    void bearerPrefixIsCaseInsensitive() {
        assertThat(ResellerAuthenticator.extractBearer("bearer abc")).isEqualTo("abc");
        assertThat(ResellerAuthenticator.extractBearer("BEARER abc")).isEqualTo("abc");
        assertThat(ResellerAuthenticator.extractBearer("Bearer  abc ")).isEqualTo("abc");
    }

    @Test
    @DisplayName("comparison is constant-time")
    void comparisonIsConstantTime() {
        // A token is a bearer secret, so an early-exit equals leaks its prefix through
        // response timing — slowly, but a caller who can retry forever does not mind.
        assertThat(ResellerAuthenticator.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(ResellerAuthenticator.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(ResellerAuthenticator.constantTimeEquals("abc", "abcd")).isFalse();
    }
}
