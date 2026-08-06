package com.skbingegalaxy.distribution.dto;

import com.skbingegalaxy.distribution.entity.Connection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A connection as the console sees it.
 *
 * <p><b>There is no credential field, and there must never be one.</b> Only
 * {@link #credentialHint} (a masked tail) and {@link #credentialConfigured} (a boolean)
 * cross this boundary. The reference itself is withheld too: it names the environment
 * variable holding the secret, which is not information a browser needs and is a useful
 * hint to an attacker who has found a way to read responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionDto {

    private Long id;
    private Long bingeId;
    private String providerCode;
    private String providerName;
    private Connection.ConnectionStatus status;
    private Connection.Environment environment;

    /** Masked tail only, e.g. {@code ••••4821}. Safe to render. */
    private String credentialHint;

    /** Whether a secret actually resolves. A hint can exist while the secret does not. */
    private boolean credentialConfigured;

    private LocalDateTime credentialExpiresAt;
    /**
     * Masked tail of the reseller key, or null when none has been issued (V3).
     *
     * <p>A hint, never the key — only its digest is stored, so the key cannot be
     * re-shown even by a caller entitled to see it. Its presence is what tells the
     * console whether the connection can be activated at all.
     */
    private String resellerKeyHint;

    private LocalDateTime resellerKeyIssuedAt;

    private LocalDateTime lastVerifiedAt;
    private LocalDateTime pausedAt;
    private String pausedReason;
    private LocalDateTime createdAt;

    /**
     * What this connection can actually do, from the provider's capability rows. Drives
     * the console: a control for an undeclared capability must be absent, not disabled
     * and hopeful.
     */
    private List<String> capabilities;

    /** Destinations reachable through this connection, with their commercial terms. */
    private List<ConnectionDestinationDto> destinations;
}
