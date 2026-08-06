package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Notices credentials that are about to lapse, or already have.
 *
 * <p><b>Why a sweep and not a page.</b> Both facts were already computed — but only when
 * someone opened the health console. An expired credential stops a channel <em>silently</em>:
 * no error reaches the venue, bookings simply cease, and the failure looks exactly like a
 * channel with no demand. Waiting for someone to visit a page is not a control.
 *
 * <p>Google Actions Center rotates basic-auth every six months, so this is a scheduled
 * certainty rather than an edge case.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialWatchService {

    private final ConnectionRepository connectionRepository;
    private final CredentialStore credentialStore;

    @Value("${distribution.credentials.expiry-warning-days:30}")
    private int expiryWarningDays;

    /** What one sweep found. Returned rather than only logged so it can be asserted. */
    public record Findings(int expiringSoon, int alreadyUnusable) {
        public boolean isEmpty() { return expiringSoon == 0 && alreadyUnusable == 0; }
    }

    /**
     * A connection is <b>degraded</b> when its credential no longer resolves — it is
     * reachable in principle and cannot authenticate in practice, which is exactly what
     * DEGRADED means. Marking it here rather than waiting for a request to fail means the
     * health console and the connections list agree before anyone is surprised.
     *
     * <p>PAUSED and REVOKED are left alone: a paused connection is not expected to work,
     * and demoting a deliberate operator decision to DEGRADED would erase the reason it
     * is off.
     */
    @Transactional
    public Findings sweep() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime warnBefore = now.plusDays(expiryWarningDays);

        List<Connection> expiring = connectionRepository
            .findByCredentialExpiresAtBeforeAndStatusNot(warnBefore, Connection.ConnectionStatus.REVOKED);

        for (Connection c : expiring) {
            boolean lapsed = c.getCredentialExpiresAt().isBefore(now);
            log.warn("Credential for connection {} (binge {}, provider {}) {} on {}",
                c.getId(), c.getBingeId(), c.getProviderCode(),
                lapsed ? "EXPIRED" : "expires", c.getCredentialExpiresAt());
        }

        int unusable = 0;
        for (Connection c : connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE)) {
            if (c.getCredentialRef() == null) continue;
            if (credentialStore.resolve(c.getCredentialRef()).isEmpty()) {
                c.setStatus(Connection.ConnectionStatus.DEGRADED);
                connectionRepository.save(c);
                unusable++;
                log.error("Connection {} (binge {}) marked DEGRADED — credential '{}' no longer resolves",
                    c.getId(), c.getBingeId(), c.getCredentialRef());
            }
        }

        Findings findings = new Findings(expiring.size(), unusable);
        if (!findings.isEmpty()) {
            log.warn("Credential sweep: {} expiring within {} days, {} already unusable",
                findings.expiringSoon(), expiryWarningDays, findings.alreadyUnusable());
        }
        return findings;
    }
}
