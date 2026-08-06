package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Inbound reseller authentication — the credential direction that had no code until the
 * OCTO seam existed, and then had the WRONG code.
 *
 * <p>This used to resolve {@code credentialRef}: the pointer to the secret SK Binge
 * presents <em>to</em> a provider. A reseller presents a different secret, in the
 * opposite direction. The mismatch was total for the only provider anyone can currently
 * use — SIMULATOR is PLATFORM_MANAGED, so {@code create} refuses a credential reference
 * and the field is NULL by construction, and the old loop skipped every connection with a
 * null ref. No reseller could authenticate against any connection that could exist, and
 * the 401 was indistinguishable from a wrong key.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OCTO reseller authentication")
class ResellerAuthenticatorTest {

    @Mock private ConnectionRepository connectionRepository;
    @InjectMocks private ResellerAuthenticator authenticator;

    private static final String KEY = "skbg_octo_TEST-KEY-VALUE";
    private static final String KEY_HASH = ResellerKeys.sha256Hex(KEY);

    private static Connection withKey(Connection.ConnectionStatus status) {
        return Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
            .status(status).resellerKeyHash(KEY_HASH).build();
    }

    @Test
    @DisplayName("a valid key resolves to its connection")
    void validKeyResolves() {
        when(connectionRepository.findByResellerKeyHash(KEY_HASH))
            .thenReturn(Optional.of(withKey(Connection.ConnectionStatus.ACTIVE)));

        assertThat(authenticator.authenticate("Bearer " + KEY))
            .map(Connection::getId).contains(5L);
    }

    @Test
    @DisplayName("a PLATFORM_MANAGED connection authenticates, because the key is its own field")
    void platformManagedConnectionsCanAuthenticate() {
        // The regression. SIMULATOR carries no credentialRef by design; reading that
        // field for inbound auth made the entire OCTO surface unreachable.
        Connection simulator = withKey(Connection.ConnectionStatus.ACTIVE);
        assertThat(simulator.getCredentialRef()).isNull();
        when(connectionRepository.findByResellerKeyHash(KEY_HASH))
            .thenReturn(Optional.of(simulator));

        assertThat(authenticator.authenticate("Bearer " + KEY)).isPresent();
    }

    @Test
    @DisplayName("only ACTIVE connections authenticate")
    void onlyActiveConnectionsAuthenticate() {
        // Pausing is how an operator stops a channel. A key that still worked would
        // ignore them.
        for (Connection.ConnectionStatus status : new Connection.ConnectionStatus[]{
                Connection.ConnectionStatus.PENDING,
                Connection.ConnectionStatus.PAUSED,
                Connection.ConnectionStatus.REVOKED,
                Connection.ConnectionStatus.DEGRADED,
                Connection.ConnectionStatus.AWAITING_PROVIDER}) {
            when(connectionRepository.findByResellerKeyHash(KEY_HASH))
                .thenReturn(Optional.of(withKey(status)));

            assertThat(authenticator.authenticate("Bearer " + KEY))
                .as("%s must not authenticate", status)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("a wrong key is refused")
    void wrongKeyRefused() {
        when(connectionRepository.findByResellerKeyHash(anyString()))
            .thenReturn(Optional.empty());

        assertThat(authenticator.authenticate("Bearer wrong")).isEmpty();
    }

    @Test
    @DisplayName("a connection with no key issued authenticates nobody")
    void connectionWithoutAKeyAuthenticatesNobody() {
        // Nothing hashes to null, so the lookup cannot match — asserted rather than
        // assumed, because "no key" reading as "no key required" is the failure that
        // would open every venue at once.
        when(connectionRepository.findByResellerKeyHash(anyString()))
            .thenReturn(Optional.empty());

        assertThat(authenticator.authenticate("Bearer anything")).isEmpty();
    }

    @Test
    @DisplayName("malformed and missing Authorization headers are refused without a lookup")
    void malformedHeadersRefused() {
        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("")).isEmpty();
        assertThat(authenticator.authenticate("Basic abc")).isEmpty();
        assertThat(authenticator.authenticate("Bearer ")).isEmpty();
        verifyNoInteractions(connectionRepository);
    }

    @Test
    @DisplayName("the Bearer prefix is case-insensitive, the key is not")
    void bearerPrefixIsCaseInsensitive() {
        assertThat(ResellerAuthenticator.extractBearer("bearer abc")).isEqualTo("abc");
        assertThat(ResellerAuthenticator.extractBearer("BEARER abc")).isEqualTo("abc");
        assertThat(ResellerAuthenticator.extractBearer("Bearer  abc ")).isEqualTo("abc");
    }
}
