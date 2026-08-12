package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.dto.*;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.octo.ResellerKeys;
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
     * Destinations a connection may actually be pointed at.
     *
     * <p><b>The catalogue that had no reader.</b> {@link #enableDestination} takes a
     * {@code destinationCode}, and nothing exposed the codes — so the step between
     * creating a connection and having a channel that can sell was reachable only by an
     * operator who already knew the values and posted them by hand.
     *
     * <p>Filtered to exactly what {@link #enableDestination} would accept: active, and
     * operated by the given provider. Offering a choice the server then refuses is worse
     * than offering none, because the refusal arrives after the operator has committed to
     * commercial terms.
     */
    public List<DestinationDto> listReachableDestinations(String providerCode) {
        return destinationRepository.findAll().stream()
            .filter(Destination::isActive)
            .filter(d -> providerCode == null
                      || d.getOperatedByProviderCode().equalsIgnoreCase(providerCode))
            .sorted(Comparator.comparing(Destination::getDisplayName))
            .map(d -> DestinationDto.builder()
                .code(d.getCode())
                .displayName(d.getDisplayName())
                .operatedByProviderCode(d.getOperatedByProviderCode())
                .deliversReservations(d.isDeliversReservations())
                .build())
            .toList();
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
     * Issue the key a reseller will present to SK Binge for this connection (V3).
     *
     * <p><b>Returned exactly once.</b> Only the digest is stored, so this response is the
     * single moment the key exists in readable form. That is a deliberate trade: an
     * operator who loses it issues a new one, which is a minor inconvenience, whereas a
     * recoverable key means a database dump is a set of working credentials.
     *
     * <p>Re-issuing invalidates the previous key immediately — there is one live key per
     * connection. Overlapping keys would be friendlier to rotate but would mean a
     * compromised key stays valid after the operator believes they have replaced it,
     * which is the opposite of what rotation is for.
     */
    @Transactional
    public IssuedResellerKey issueResellerKey(Long bingeId, Long connectionId) {
        Connection c = ownedConnection(bingeId, connectionId);
        if (c.getStatus() == Connection.ConnectionStatus.REVOKED) {
            throw new BusinessException("A revoked connection cannot be given a key.");
        }

        ResellerKeys.IssuedKey issued = ResellerKeys.issue();
        boolean replacing = c.getResellerKeyHash() != null;

        c.setResellerKeyHash(issued.hash());
        c.setResellerKeyHint(issued.hint());
        c.setResellerKeyIssuedAt(LocalDateTime.now(ZoneOffset.UTC));
        connectionRepository.save(c);

        // The hint is safe to log; the key and its digest are not.
        log.info("Binge {} {} the reseller key for connection {} (hint {})",
            bingeId, replacing ? "ROTATED" : "issued", connectionId, issued.hint());

        return new IssuedResellerKey(issued.plaintext(), issued.hint(), replacing);
    }

    /**
     * @param key the plaintext, present in this object and nowhere else, ever again
     * @param replacedPrevious true when an older key was just invalidated, so the console
     *                         can say so rather than letting an operator discover it
     */
    public record IssuedResellerKey(String key, String hint, boolean replacedPrevious) {}

    /**
     * Verify a connection and, if everything it needs is in place, put it live.
     *
     * <p><b>The transition that did not exist.</b> {@link #create} deliberately produces
     * PENDING and {@link #resume} deliberately returns to PENDING, but nothing anywhere
     * could reach ACTIVE — and ACTIVE is what {@code ResellerAuthenticator} requires to
     * authenticate a reseller and what a listing requires to publish. A venue could
     * therefore complete every step the console offered and still have a channel that
     * could not sell, with no error to explain why.
     *
     * <p><b>Verification, not a switch.</b> Each precondition below is something whose
     * absence makes the channel fail later, further from the cause:
     * <ul>
     *   <li>the provider must still be active — a super-admin may have withdrawn it
     *       since the connection was created;</li>
     *   <li>the credential must resolve <em>now</em>, not merely have a reference. A
     *       secret can be rotated away between creation and activation, and "configured"
     *       has to mean the secret is actually there;</li>
     *   <li>the credential must not already be expired, or the first reseller call
     *       authenticates against nothing;</li>
     *   <li>at least one destination must be enabled, or the connection is live and
     *       reaches nowhere.</li>
     * </ul>
     *
     * <p><b>What this deliberately does NOT do.</b> It does not certify the connection
     * with the provider. For every real provider there is a certification, a pilot or a
     * signed agreement between "configured correctly" and "permitted to sell", and no
     * amount of local checking can stand in for it. Those providers are seeded inactive
     * and a super-admin turning one on is the platform's record that the external step
     * happened. This method verifies only the half SK Binge owns.
     */
    @Transactional
    public ConnectionDto activate(Long bingeId, Long connectionId) {
        Connection c = ownedConnection(bingeId, connectionId);

        if (c.getStatus() == Connection.ConnectionStatus.ACTIVE) {
            // Idempotent: activating an active connection is what a double-clicked button
            // does, and it is not an error worth showing an operator.
            return toDto(c);
        }
        if (c.getStatus() == Connection.ConnectionStatus.REVOKED) {
            throw new BusinessException(
                "A revoked connection cannot be activated. Create a new one.");
        }
        if (c.getStatus() == Connection.ConnectionStatus.PAUSED) {
            throw new BusinessException(
                "Resume this connection before activating it.");
        }

        Provider provider = providerRepository.findById(c.getProviderCode())
            .orElseThrow(() -> new ResourceNotFoundException("Provider", "code", c.getProviderCode()));
        if (!provider.isActive()) {
            throw new BusinessException(provider.getDisplayName()
                + " is no longer available for connections.");
        }

        if (requiresCredential(provider)) {
            if (c.getCredentialRef() == null) {
                throw new BusinessException(provider.getDisplayName()
                    + " requires a provisioned credential before it can go live.");
            }
            if (credentialStore.resolve(c.getCredentialRef()).isEmpty()) {
                throw new BusinessException(
                    "The credential for this connection is no longer provisioned. "
                  + "Re-provision it on distribution-service, then activate.");
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            if (c.getCredentialExpiresAt() != null && c.getCredentialExpiresAt().isBefore(now)) {
                throw new BusinessException(
                    "This connection's credential expired on " + c.getCredentialExpiresAt().toLocalDate()
                  + ". Rotate it before activating.");
            }
        }

        boolean reachesSomewhere = connectionDestinationRepository.findByConnectionId(connectionId)
            .stream().anyMatch(ConnectionDestination::isEnabled);
        if (!reachesSomewhere) {
            throw new BusinessException(
                "Add at least one enabled destination before activating this connection.");
        }

        // The inbound half. OCTO is supplier-hosted, so a reseller reaches this venue by
        // presenting a key SK Binge issued — and without one, ResellerAuthenticator can
        // match nothing. Activating anyway would produce the exact failure this method
        // exists to prevent: a connection the console calls live, that answers every
        // reseller with a 401 indistinguishable from a wrong key.
        if (c.getResellerKeyHash() == null) {
            throw new BusinessException(
                "Issue a reseller key before activating: without one no reseller can "
              + "authenticate against this connection.");
        }

        c.setStatus(Connection.ConnectionStatus.ACTIVE);
        c.setLastVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        c.setPausedAt(null);
        c.setPausedReason(null);
        log.info("Binge {} ACTIVATED connection {} to provider {}",
            bingeId, connectionId, c.getProviderCode());
        return toDto(connectionRepository.save(c));
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
            // The hint only; the key itself is unrecoverable by design (V3).
            .resellerKeyHint(c.getResellerKeyHint())
            .resellerKeyIssuedAt(c.getResellerKeyIssuedAt())
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
