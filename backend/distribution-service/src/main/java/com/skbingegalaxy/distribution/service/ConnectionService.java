package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.dto.*;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A venue's connections to distribution providers (slice 3).
 *
 * <p>Every method is scoped by {@code bingeId}, taken from the authenticated request and
 * never from the body. A connection is a venue's commercial relationship with a
 * provider, so letting one venue address another's connection would be worse than an
 * ordinary IDOR: it would let a venue pause a competitor's sales channel.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectionService {

    private final ConnectionRepository connectionRepository;
    private final ConnectionDestinationRepository connectionDestinationRepository;
    private final ProviderRepository providerRepository;
    private final ProviderCapabilityRepository capabilityRepository;
    private final DestinationRepository destinationRepository;
    private final CredentialStore credentialStore;

    // ── Catalogue ────────────────────────────────────────────────────────────

    /**
     * Providers a venue may connect to.
     *
     * <p>Only {@code active} ones, which today means the simulator alone: every real
     * provider is seeded inactive and stays that way until a super-admin turns it on.
     * That is the enforcement point for the still-open commercial question of whether
     * any experiences reseller will list private venue hire at all — nobody can create a
     * Viator connection by guessing a provider code.
     */
    public List<ProviderDto> listConnectableProviders() {
        List<Provider> providers = providerRepository.findByActiveTrueOrderByDisplayNameAsc();
        if (providers.isEmpty()) return List.of();

        Map<String, List<ProviderCapability>> caps = capabilityRepository
            .findByProviderCodeIn(providers.stream().map(Provider::getCode).toList())
            .stream()
            .collect(Collectors.groupingBy(ProviderCapability::getProviderCode));

        return providers.stream().map(p -> ProviderDto.builder()
            .code(p.getCode())
            .displayName(p.getDisplayName())
            .providerKind(p.getProviderKind())
            .authMethod(p.getAuthMethod())
            .certificationState(p.getCertificationState())
            .docsUrl(p.getDocsUrl())
            .requiresCredential(requiresCredential(p))
            .credentialSubmissionSupported(credentialStore.supportsWrite())
            .capabilities(caps.getOrDefault(p.getCode(), List.of()).stream()
                .map(c -> ProviderDto.CapabilityDto.builder()
                    .key(c.getCapabilityKey())
                    .enabled(c.isEnabled())
                    .intValue(c.getIntValue())
                    .notes(c.getNotes())
                    .build())
                .toList())
            .build()).toList();
    }

    /**
     * A PLATFORM_MANAGED provider authenticates with SK Binge's own arrangement, so the
     * venue supplies nothing. Every other auth method needs a provisioned secret —
     * including SFTP_FEED and CONTRACT_ONLY, where the "credential" is a feed account or
     * a signed agreement rather than a key.
     */
    private boolean requiresCredential(Provider p) {
        return p.getAuthMethod() != Provider.AuthMethod.PLATFORM_MANAGED;
    }

    // ── Connections ──────────────────────────────────────────────────────────

    public List<ConnectionDto> listForBinge(Long bingeId) {
        List<Connection> connections = connectionRepository.findByBingeIdOrderByCreatedAtDesc(bingeId);
        if (connections.isEmpty()) return List.of();

        Map<Long, List<ConnectionDestination>> destsByConnection = connectionDestinationRepository
            .findByConnectionIdIn(connections.stream().map(Connection::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(ConnectionDestination::getConnectionId));

        Map<String, Provider> providers = providerRepository.findAll().stream()
            .collect(Collectors.toMap(Provider::getCode, p -> p));
        Map<String, List<ProviderCapability>> caps = capabilityRepository
            .findByProviderCodeIn(providers.keySet())
            .stream()
            .collect(Collectors.groupingBy(ProviderCapability::getProviderCode));
        Map<String, Destination> destinations = destinationRepository.findAll().stream()
            .collect(Collectors.toMap(Destination::getCode, d -> d));

        return connections.stream()
            .map(c -> toDto(c, providers.get(c.getProviderCode()),
                caps.getOrDefault(c.getProviderCode(), List.of()),
                destsByConnection.getOrDefault(c.getId(), List.of()),
                destinations))
            .toList();
    }

    @Transactional
    public ConnectionDto create(Long bingeId, Long actorId, CreateConnectionRequest request) {
        Provider provider = providerRepository.findById(request.getProviderCode())
            .orElseThrow(() -> new ResourceNotFoundException("Provider", "code", request.getProviderCode()));

        // Fail closed on an inactive provider. Seeding every real provider inactive is
        // only a control if creating a connection actually checks it.
        if (!provider.isActive()) {
            throw new BusinessException(
                provider.getDisplayName() + " is not yet available for connections.");
        }

        Connection.Environment env = request.getEnvironment() == null
            ? Connection.Environment.SANDBOX
            : request.getEnvironment();

        // Matches uk_connection_binge_provider_env. Checked here so the operator gets a
        // sentence instead of a constraint-violation stack trace; the unique index is
        // still what makes it true under concurrency.
        connectionRepository.findByBingeIdAndProviderCodeAndEnvironment(
                bingeId, provider.getCode(), env)
            .ifPresent(existing -> {
                throw new BusinessException("This venue already has a "
                    + env.name().toLowerCase() + " connection to " + provider.getDisplayName() + ".");
            });

        String credentialRef = trimToNull(request.getCredentialRef());
        if (requiresCredential(provider)) {
            if (credentialRef == null) {
                throw new BusinessException(provider.getDisplayName()
                    + " requires a provisioned credential reference.");
            }
            // Verify the secret actually resolves BEFORE creating the row. A connection
            // that looks configured but cannot authenticate is worse than none: it fails
            // later, in a scheduled job, where the cause is far from the cause.
            if (credentialStore.resolve(credentialRef).isEmpty()) {
                throw new BusinessException(
                    "No credential is provisioned for reference '" + credentialRef
                  + "'. Provision it on distribution-service, then create the connection.");
            }
        } else if (credentialRef != null) {
            // Refused rather than ignored: silently dropping it would leave the operator
            // believing a credential is in play when none is.
            throw new BusinessException(provider.getDisplayName()
                + " is platform-managed and takes no credential reference.");
        }

        Connection connection = Connection.builder()
            .bingeId(bingeId)
            .providerCode(provider.getCode())
            .environment(env)
            // PENDING, never ACTIVE. Certification, a pilot or a signed agreement stands
            // between a created connection and a live one for every real provider.
            .status(Connection.ConnectionStatus.PENDING)
            .credentialRef(credentialRef)
            .credentialHint(mask(credentialRef))
            .createdBy(actorId)
            .build();

        Connection saved = connectionRepository.save(connection);
        // The REFERENCE is safe to log; the secret is never in scope here to leak.
        log.info("Binge {} created {} connection {} to provider {} (ref={})",
            bingeId, env, saved.getId(), provider.getCode(), credentialRef);

        return toDto(saved, provider,
            capabilityRepository.findByProviderCode(provider.getCode()),
            List.of(), Map.of());
    }

    /**
     * Pause stops <b>all</b> traffic on a connection. Distinct from stop-sell on a single
     * destination, which halts new sales while still honouring reservations already
     * taken — conflating them would either strand travellers or fail to stop the bleeding.
     */
    @Transactional
    public ConnectionDto pause(Long bingeId, Long connectionId, String reason) {
        Connection c = ownedConnection(bingeId, connectionId);
        if (c.getStatus() == Connection.ConnectionStatus.REVOKED) {
            throw new BusinessException("A revoked connection cannot be paused.");
        }
        c.setStatus(Connection.ConnectionStatus.PAUSED);
        c.setPausedAt(LocalDateTime.now(ZoneOffset.UTC));
        c.setPausedReason(trimToNull(reason));
        log.info("Binge {} paused connection {} ({})", bingeId, connectionId, reason);
        return toDto(connectionRepository.save(c));
    }

    @Transactional
    public ConnectionDto resume(Long bingeId, Long connectionId) {
        Connection c = ownedConnection(bingeId, connectionId);
        if (c.getStatus() != Connection.ConnectionStatus.PAUSED) {
            throw new BusinessException("Only a paused connection can be resumed.");
        }
        // Back to PENDING, not ACTIVE. Resuming is the venue's decision; being live again
        // is the provider's, and only a successful verification proves it.
        c.setStatus(Connection.ConnectionStatus.PENDING);
        c.setPausedAt(null);
        c.setPausedReason(null);
        return toDto(connectionRepository.save(c));
    }

    /**
     * Revocation is terminal. The row is kept rather than deleted because settlements and
     * reservations reference it, and deleting the connection a booking arrived through
     * would orphan money that still needs reconciling.
     */
    @Transactional
    public ConnectionDto revoke(Long bingeId, Long connectionId, String reason) {
        Connection c = ownedConnection(bingeId, connectionId);
        c.setStatus(Connection.ConnectionStatus.REVOKED);
        c.setPausedAt(LocalDateTime.now(ZoneOffset.UTC));
        c.setPausedReason(trimToNull(reason));
        c.setCredentialRef(null);   // the pointer is useless now and is not kept around
        c.setCredentialHint(null);
        log.warn("Binge {} REVOKED connection {} ({})", bingeId, connectionId, reason);
        return toDto(connectionRepository.save(c));
    }

    /**
     * Point a connection at a destination, on stated commercial terms.
     *
     * <p><b>The step that was missing.</b> A venue could create a connection but never
     * attach a destination to it, and a listing requires a
     * {@code connectionDestinationId} — so the entire publish chain dead-ended at the
     * first hop. Everything downstream (listings, readiness, publish) was unreachable.
     */
    @Transactional
    public ConnectionDestinationDto enableDestination(Long bingeId, Long connectionId,
                                                      EnableDestinationRequest request) {
        Connection connection = ownedConnection(bingeId, connectionId);
        if (connection.getStatus() == Connection.ConnectionStatus.REVOKED) {
            throw new BusinessException("A revoked connection cannot reach new destinations.");
        }

        Destination destination = destinationRepository.findById(request.getDestinationCode())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Destination", "code", request.getDestinationCode()));

        // The check that had nowhere to live until this endpoint existed. Every real
        // destination is seeded inactive; without this a venue could publish to a
        // marketplace the platform has not turned on, which is the same class of bug as
        // publishing to a destination the venue never enabled.
        if (!destination.isActive()) {
            throw new BusinessException(
                destination.getDisplayName() + " is not yet available as a destination.");
        }

        // A destination must be operated by the provider this connection authenticates
        // against, or the credential is for the wrong system entirely. A Bokun
        // connection reaching Viator is legitimate and handled by the provider's
        // capability rows; a Viator connection reaching GetYourGuide is not.
        if (!destination.getOperatedByProviderCode().equals(connection.getProviderCode())) {
            throw new BusinessException(destination.getDisplayName()
                + " is not reachable through a " + connection.getProviderCode() + " connection.");
        }

        connectionDestinationRepository
            .findByConnectionIdAndDestinationCode(connectionId, destination.getCode())
            .ifPresent(existing -> {
                throw new BusinessException(
                    "This connection already reaches " + destination.getDisplayName() + ".");
            });

        ConnectionDestination saved = connectionDestinationRepository.save(
            ConnectionDestination.builder()
                .connectionId(connectionId)
                .destinationCode(destination.getCode())
                .enabled(request.isEnabled())
                .commissionBps(request.getCommissionBps())
                .paymentResponsibility(request.getPaymentResponsibility())
                .settlementModel(request.getSettlementModel())
                .safetyInventory(request.getSafetyInventory())
                .build());

        log.info("Binge {} pointed connection {} at destination {} (enabled={})",
            bingeId, connectionId, destination.getCode(), request.isEnabled());

        return ConnectionDestinationDto.builder()
            .id(saved.getId())
            .destinationCode(saved.getDestinationCode())
            .destinationName(destination.getDisplayName())
            .enabled(saved.isEnabled())
            .commissionBps(saved.getCommissionBps())
            .paymentResponsibility(saved.getPaymentResponsibility())
            .settlementModel(saved.getSettlementModel())
            .safetyInventory(saved.getSafetyInventory())
            .stopSell(saved.isStopSell())
            .stopSellReason(saved.getStopSellReason())
            .deliversReservations(destination.isDeliversReservations())
            .build();
    }

    private Connection ownedConnection(Long bingeId, Long connectionId) {
        return connectionRepository.findByIdAndBingeId(connectionId, bingeId)
            .orElseThrow(() -> new ResourceNotFoundException("Connection", "id", connectionId));
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private ConnectionDto toDto(Connection c) {
        return toDto(c, providerRepository.findById(c.getProviderCode()).orElse(null),
            capabilityRepository.findByProviderCode(c.getProviderCode()),
            connectionDestinationRepository.findByConnectionId(c.getId()),
            destinationRepository.findAll().stream()
                .collect(Collectors.toMap(Destination::getCode, d -> d)));
    }

    private ConnectionDto toDto(Connection c, Provider provider,
                                List<ProviderCapability> capabilities,
                                List<ConnectionDestination> destinations,
                                Map<String, Destination> destinationCatalogue) {
        return ConnectionDto.builder()
            .id(c.getId())
            .bingeId(c.getBingeId())
            .providerCode(c.getProviderCode())
            .providerName(provider == null ? c.getProviderCode() : provider.getDisplayName())
            .status(c.getStatus())
            .environment(c.getEnvironment())
            .credentialHint(c.getCredentialHint())
            // Resolved live rather than trusted: a hint persists after the secret is
            // rotated away, and "configured" must mean the secret is actually there.
            .credentialConfigured(c.getCredentialRef() != null
                && credentialStore.resolve(c.getCredentialRef()).isPresent())
            .credentialExpiresAt(c.getCredentialExpiresAt())
            .lastVerifiedAt(c.getLastVerifiedAt())
            .pausedAt(c.getPausedAt())
            .pausedReason(c.getPausedReason())
            .createdAt(c.getCreatedAt())
            // Only ENABLED capabilities reach the console. A disabled row exists to carry
            // its evidence for a reviewer, not to light up a control.
            .capabilities(capabilities.stream()
                .filter(ProviderCapability::isEnabled)
                .map(ProviderCapability::getCapabilityKey)
                .sorted()
                .toList())
            .destinations(destinations.stream()
                .map(d -> {
                    Destination cat = destinationCatalogue.get(d.getDestinationCode());
                    return ConnectionDestinationDto.builder()
                        .id(d.getId())
                        .destinationCode(d.getDestinationCode())
                        .destinationName(cat == null ? d.getDestinationCode() : cat.getDisplayName())
                        .enabled(d.isEnabled())
                        .commissionBps(d.getCommissionBps())
                        .paymentResponsibility(d.getPaymentResponsibility())
                        .settlementModel(d.getSettlementModel())
                        .safetyInventory(d.getSafetyInventory())
                        .stopSell(d.isStopSell())
                        .stopSellReason(d.getStopSellReason())
                        .deliversReservations(cat != null && cat.isDeliversReservations())
                        .build();
                })
                .toList())
            .build();
    }

    /** Last four characters only. Never the reference itself. */
    static String mask(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) return null;
        String trimmed = credentialRef.trim();
        String tail = trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
        return "••••" + tail;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
