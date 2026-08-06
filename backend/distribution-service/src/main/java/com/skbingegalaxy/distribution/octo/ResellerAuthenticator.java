package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    /**
     * Resolve a Bearer token to the connection it belongs to.
     *
     * <p><b>It checks the INBOUND key, not the outbound credential.</b> This used to
     * resolve {@code credentialRef} — the pointer to the secret SK Binge presents
     * <em>to</em> a provider — and compare it against what a reseller presented to
     * <em>us</em>. Two different secrets in opposite directions. The effect was absolute
     * for the only usable provider: SIMULATOR is PLATFORM_MANAGED, so
     * {@code ConnectionService.create} refuses a credential reference and
     * {@code credentialRef} is NULL by construction, and this loop skipped every
     * connection with a null ref. No reseller could authenticate against any connection
     * that could actually exist, and the 401 looked exactly like a wrong key.
     *
     * <p><b>One indexed read, not a scan.</b> The previous loop resolved and compared
     * every ACTIVE connection's secret in turn, so the cost of one reseller request grew
     * with the number of venues on the platform — and every one of those comparisons
     * touched a live secret. Looking up by digest touches one row and no secret at all.
     *
     * <p>Only ACTIVE connections: a paused or revoked one must not authenticate
     * anything. Returns empty rather than throwing, so the caller decides the status
     * code, and a bad key and an unknown key stay indistinguishable to that caller.
     */
    public Optional<Connection> authenticate(String authorizationHeader) {
        String token = extractBearer(authorizationHeader);
        if (token == null) return Optional.empty();

        Optional<Connection> match = connectionRepository
            .findByResellerKeyHash(ResellerKeys.sha256Hex(token))
            .filter(c -> c.getStatus() == Connection.ConnectionStatus.ACTIVE)
            // Re-verified in constant time even though the lookup already matched: the
            // index proves the digests are equal, and this proves it without depending
            // on the database's comparison having been the constant-time one.
            .filter(c -> ResellerKeys.matches(token, c.getResellerKeyHash()));

        if (match.isEmpty()) {
            // Neither the key nor the digest is logged — a rejected key is still a
            // secret, and one that appears in logs is one an operator may paste.
            log.warn("OCTO request rejected: no ACTIVE connection matches the presented key");
        }
        return match;
    }

    static String extractBearer(String header) {
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.length() < 8 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

}
