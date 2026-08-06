package com.skbingegalaxy.distribution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One venue's distribution health (slice 7).
 *
 * <p><b>Named problems, not a score.</b> A single "health: 82%" tells an operator
 * nothing they can act on and hides which of several unrelated failures is happening.
 * Each field here is a thing someone can go and fix, and {@link #alerts} carries them in
 * the order they should be dealt with.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributionHealthDto {

    private int connectionsTotal;
    private int connectionsActive;
    private int connectionsDegraded;
    private int connectionsPaused;

    /** Credentials expiring inside the warning window — Google rotates every 6 months. */
    private int credentialsExpiringSoon;

    /** Connections whose credential reference no longer resolves to a secret at all. */
    private int credentialsMissing;

    private int listingsLive;
    private int listingsBlocked;

    /** Inbound messages that errored and are retryable. */
    private int inboxFailed;

    /**
     * Messages that arrived out of order and were correctly set aside. Reported because
     * a SPIKE means a provider's delivery is degrading, even though each individual
     * SUPERSEDED row is the system working as designed.
     */
    private int inboxSuperseded;

    private LocalDateTime generatedAt;

    /** Ordered worst-first. Empty means genuinely nothing to do. */
    private List<Alert> alerts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Alert {
        /** CRITICAL stops sales; WARNING will if ignored; INFO is context. */
        private String severity;
        private String message;
        /** What to do about it. An alert with no action is just anxiety. */
        private String action;
    }
}
