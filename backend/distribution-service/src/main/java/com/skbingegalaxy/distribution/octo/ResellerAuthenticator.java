package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Authenticates an INBOUND reseller against a connection (OCTO).
 *
 * <p>This is the credential direction that had no code until now. Everything else in
 * this service handles secrets SK Binge presents to a provider; OCTO inverts it — <b>the
 * supplier hosts the endpoint</b> and each reseller connects with a key issued per
 * reseller↔supplier pair. The token therefore identifies WHICH connection is calling,
 * and with it the venue, the destination and the commercial terms.
 *
 * <p>Because SK Binge issues these keys itself, none of this waits on a provider
 * agreement — which is the property the OCTO-first sequencing was chosen for.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResellerAuthenticator {

    private final ConnectionRepository connectionRepository;
    private final CredentialStore credentialStore;

    /**
     * Resolve a Bearer token to the connection it belongs to.
     *
     * <p><b>Constant-time comparison.</b> A token is a bearer secret, so an early-exit
     * {@code equals} leaks its prefix through response timing — slowly, but a caller who
     * can retry indefinitely does not care how slowly. The scan is over ACTIVE
     * connections only: a paused or revoked connection must not authenticate anything.
     *
     * <p>Returns empty rather than throwing, so the caller decides the status code. A
     * bad token and an unknown token are deliberately indistinguishable to the caller.
     */
    public Optional<Connection> authenticate(String authorizationHeader) {
        String token = extractBearer(authorizationHeader);
        if (token == null) return Optional.empty();

        for (Connection c : connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE)) {
            if (c.getCredentialRef() == null) continue;
            Optional<String> expected = credentialStore.resolve(c.getCredentialRef());
            if (expected.isPresent() && constantTimeEquals(expected.get(), token)) {
                return Optional.of(c);
            }
        }
        // The REF is never logged here and neither is the token — a rejected token is
        // still a secret, and one that appears in logs is one an operator may paste.
        log.warn("OCTO request rejected: no ACTIVE connection matches the presented token");
        return Optional.empty();
    }

    static String extractBearer(String header) {
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.length() < 8 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
