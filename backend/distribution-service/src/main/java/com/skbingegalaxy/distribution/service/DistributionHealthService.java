package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.dto.DistributionHealthDto;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Distribution health for one venue (slice 7).
 *
 * <p><b>Why this is not a score.</b> "Health: 82%" is unactionable and hides which of
 * several unrelated failures is happening — an expired credential and a blocked listing
 * are different jobs for different people. This produces named problems with the action
 * that resolves each one.
 *
 * <p>Read-only and derived on demand. Caching it would mean an operator looking at a
 * dashboard to decide whether sales are flowing could be shown a state that is minutes
 * stale, which is the one situation where stale is worse than slow.
 */
@Service
@RequiredArgsConstructor
public class DistributionHealthService {

    private final ConnectionRepository connectionRepository;
    private final ListingMappingRepository listingRepository;
    private final ReservationInboxRepository inboxRepository;
    private final CredentialStore credentialStore;

    @Value("${distribution.credentials.expiry-warning-days:30}")
    private int expiryWarningDays;

    public DistributionHealthDto forBinge(Long bingeId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Connection> connections = connectionRepository.findByBingeIdOrderByCreatedAtDesc(bingeId);

        int active = 0, degraded = 0, paused = 0, expiringSoon = 0, missing = 0;
        for (Connection c : connections) {
            switch (c.getStatus()) {
                case ACTIVE -> active++;
                case DEGRADED -> degraded++;
                case PAUSED -> paused++;
                default -> { }
            }
            if (c.getCredentialExpiresAt() != null
                && c.getCredentialExpiresAt().isBefore(now.plusDays(expiryWarningDays))) {
                expiringSoon++;
            }
            // A connection can carry a reference whose secret was rotated away. The hint
            // survives; the ability to authenticate does not. Only REVOKED connections
            // are exempt, because clearing the pointer is part of revoking.
            if (c.getStatus() != Connection.ConnectionStatus.REVOKED
                && c.getCredentialRef() != null
                && credentialStore.resolve(c.getCredentialRef()).isEmpty()) {
                missing++;
            }
        }

        List<ListingMapping> listings = listingRepository.findByBingeId(bingeId);
        int live = (int) listings.stream()
            .filter(l -> l.getPublishState() == ListingMapping.PublishState.LIVE).count();
        int blocked = (int) listings.stream()
            .filter(l -> l.getPublishState() == ListingMapping.PublishState.BLOCKED
                      || l.getPublishState() == ListingMapping.PublishState.FAILED).count();

        List<Long> connectionIds = connections.stream().map(Connection::getId).toList();
        int failed = connectionIds.isEmpty() ? 0 : (int) inboxRepository
            .countByConnectionIdInAndStatus(connectionIds, ReservationInboxEntry.Status.FAILED);
        int superseded = connectionIds.isEmpty() ? 0 : (int) inboxRepository
            .countByConnectionIdInAndStatus(connectionIds, ReservationInboxEntry.Status.SUPERSEDED);

        List<DistributionHealthDto.Alert> alerts = new ArrayList<>();

        // Worst first. A missing credential means the channel is dead right now; an
        // expiring one means it will be. Ordering them the other way round would bury
        // the outage under the warning.
        if (missing > 0) {
            alerts.add(alert("CRITICAL",
                missing + " connection(s) have no resolvable credential — those channels cannot authenticate.",
                "Provision the secret on distribution-service, or revoke the connection."));
        }
        if (degraded > 0) {
            alerts.add(alert("CRITICAL",
                degraded + " connection(s) are DEGRADED — reachable but failing.",
                "Check the provider's status, then verify the connection."));
        }
        if (failed > 0) {
            alerts.add(alert("CRITICAL",
                failed + " inbound message(s) failed and may be lost reservations.",
                "Open the reservation inbox and retry them."));
        }
        if (expiringSoon > 0) {
            alerts.add(alert("WARNING",
                expiringSoon + " credential(s) expire within " + expiryWarningDays + " days.",
                "Rotate the secret before it lapses — an expired credential stops a channel silently."));
        }
        if (blocked > 0) {
            alerts.add(alert("WARNING",
                blocked + " listing(s) are blocked or failed and are not on sale.",
                "Open Listings and resolve the blocking items."));
        }
        if (paused > 0) {
            alerts.add(alert("INFO",
                paused + " connection(s) are paused — no traffic is flowing on them.",
                "Resume them when ready; reservations already taken are unaffected."));
        }

        return DistributionHealthDto.builder()
            .connectionsTotal(connections.size())
            .connectionsActive(active)
            .connectionsDegraded(degraded)
            .connectionsPaused(paused)
            .credentialsExpiringSoon(expiringSoon)
            .credentialsMissing(missing)
            .listingsLive(live)
            .listingsBlocked(blocked)
            .inboxFailed(failed)
            .inboxSuperseded(superseded)
            .generatedAt(now)
            .alerts(alerts)
            .build();
    }

    private static DistributionHealthDto.Alert alert(String severity, String message, String action) {
        return DistributionHealthDto.Alert.builder()
            .severity(severity).message(message).action(action).build();
    }
}
