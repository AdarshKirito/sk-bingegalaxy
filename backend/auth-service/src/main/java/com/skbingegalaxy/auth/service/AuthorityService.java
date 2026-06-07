package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.dto.AuthorityGrantDto;
import com.skbingegalaxy.auth.dto.CreateAuthorityGrantRequest;
import com.skbingegalaxy.auth.dto.CreateResourceLockRequest;
import com.skbingegalaxy.auth.dto.EffectiveAuthorityDto;
import com.skbingegalaxy.auth.dto.ResourceLockDto;
import com.skbingegalaxy.auth.entity.AuthorityGrant;
import com.skbingegalaxy.auth.entity.ResourceLock;
import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.AuthorityGrantRepository;
import com.skbingegalaxy.auth.repository.ResourceLockRepository;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.common.enums.AuthorityScope;
import com.skbingegalaxy.common.enums.UserRole;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Authority Handover — service-layer business rules.
 *
 * <h3>Invariants enforced here</h3>
 * <ul>
 *   <li>Only {@code SUPER_ADMIN} may issue, revoke, or list grants. The controller
 *       checks this; this service double-checks defensively at write paths.</li>
 *   <li>Grants may only be issued to users with role {@code ADMIN} (issuing one to a
 *       customer would be a privilege-escalation bug; issuing to a super-admin is
 *       a no-op and therefore rejected).</li>
 *   <li>Duration is clamped to [1h, 24h]. Default 4 h. Server-side enforced.</li>
 *   <li>Revoking a grant immediately invalidates the grantee's existing JWTs by
 *       calling {@link UserSessionService#revokeAllForUser}, forcing re-login. This
 *       is the production-grade default — JWT revocation list catches in-flight
 *       requests within the next refresh cycle.</li>
 *   <li>Issuing a new grant ALSO revokes the grantee's current sessions so the next
 *       request comes back with the freshly-claimed elevated JWT. Without this step,
 *       the grant would only become visible after the access-token TTL elapses.</li>
 *   <li>Locks are unique by (resourceType, resourceId). Re-locking is idempotent
 *       (returns existing) iff the lock owner matches; cross-owner re-lock is rejected
 *       so a delegated admin can't quietly steal a super-admin's lock.</li>
 *   <li>Releasing a lock requires native SUPER_ADMIN OR being the lock owner.
 *       Delegated super-admins can NEVER release a lock owned by someone else —
 *       this is the lock's primary purpose.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorityService {

    /** Default duration if request omits durationHours. Aligns with industry JIT defaults. */
    private static final int DEFAULT_DURATION_HOURS = 4;
    /** Hard cap on grant duration. Cannot be overridden via the API. */
    private static final int MAX_DURATION_HOURS = 24;

    private final AuthorityGrantRepository grantRepo;
    private final ResourceLockRepository lockRepo;
    private final UserRepository userRepo;
    private final UserSessionService sessionService;
    private final AuthAuditService audit;

    // ──────────────────────────────────────────────────────────
    // GRANTS — write
    // ──────────────────────────────────────────────────────────

    @Transactional
    public AuthorityGrantDto createGrant(Long actingSuperAdminId, CreateAuthorityGrantRequest req) {
        User actor = userRepo.findById(actingSuperAdminId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", actingSuperAdminId));
        if (actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new BusinessException("Only super-admins may issue authority grants");
        }
        User grantee = userRepo.findById(req.getGranteeUserId())
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", req.getGranteeUserId()));

        if (Objects.equals(grantee.getId(), actor.getId())) {
            throw new BusinessException("You cannot grant authority to yourself");
        }
        if (grantee.getRole() == UserRole.CUSTOMER) {
            throw new BusinessException("Authority can only be granted to admin users");
        }
        if (grantee.getRole() == UserRole.SUPER_ADMIN) {
            throw new BusinessException("Grantee is already a super-admin; grant is unnecessary");
        }
        if (!grantee.isActive()) {
            throw new BusinessException("Grantee account is inactive");
        }
        if (req.getScopes() == null || req.getScopes().isEmpty()) {
            throw new BusinessException("At least one scope is required");
        }

        int hours = req.getDurationHours() == null ? DEFAULT_DURATION_HOURS : req.getDurationHours();
        if (hours < 1 || hours > MAX_DURATION_HOURS) {
            // Bean Validation should have caught this; defensive belt+braces.
            throw new BusinessException("durationHours must be between 1 and " + MAX_DURATION_HOURS);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        AuthorityGrant grant = AuthorityGrant.builder()
            .granteeUserId(grantee.getId())
            .grantedBy(actor.getId())
            .scopes(new HashSet<>(req.getScopes()))
            .reason(req.getReason().trim())
            .expiresAt(now.plusHours(hours))
            .build();
        grant = grantRepo.save(grant);

        // Force grantee's existing sessions to refresh so the new grant is reflected
        // in their next JWT immediately. Without this they would have to wait for
        // their access token to expire (15 min by default).
        try {
            sessionService.revokeAllForUser(
                grantee.getId(), actor.getId(),
                "Forced refresh: AuthorityGrant#" + grant.getId() + " issued");
        } catch (Exception ex) {
            log.warn("Failed to revoke sessions for grantee {} during grant {}: {}",
                grantee.getId(), grant.getId(), ex.getMessage());
            // Non-fatal — grant is still valid; grantee will pick it up at next refresh.
        }

        audit.record(
            AuthAuditService.EventType.AUTHORITY_GRANTED,
            actor.getId(), actor.getRole(),
            grantee.getId(), grantee.getEmail(),
            true, null,
            "scopes=" + req.getScopes() + ", expiresAt=" + grant.getExpiresAt()
                + ", reason=" + grant.getReason());

        return toDto(grant, grantee, actor, /* revoker */ null);
    }

    @Transactional
    public AuthorityGrantDto revokeGrant(Long actingSuperAdminId, Long grantId, String reason) {
        User actor = userRepo.findById(actingSuperAdminId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", actingSuperAdminId));
        if (actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new BusinessException("Only super-admins may revoke authority grants");
        }
        AuthorityGrant grant = grantRepo.findById(grantId)
            .orElseThrow(() -> new ResourceNotFoundException("AuthorityGrant", "id", grantId));

        String safeReason = (reason == null || reason.isBlank())
            ? "Revoked by super-admin" : reason.trim();
        int updated = grantRepo.revoke(grant.getId(), actor.getId(), safeReason, LocalDateTime.now(ZoneOffset.UTC));
        if (updated == 0) {
            // Already revoked — idempotent. Return current state.
            log.info("Grant {} was already revoked; returning current state", grantId);
        } else {
            // Revoke grantee sessions so elevation is dropped immediately.
            try {
                sessionService.revokeAllForUser(grant.getGranteeUserId(), actor.getId(),
                    "Forced refresh: AuthorityGrant#" + grant.getId() + " revoked");
            } catch (Exception ex) {
                log.warn("Failed to revoke sessions for grantee {} during grant-revoke {}: {}",
                    grant.getGranteeUserId(), grant.getId(), ex.getMessage());
            }
        }
        AuthorityGrant fresh = grantRepo.findById(grantId).orElse(grant);
        User grantee = userRepo.findById(fresh.getGranteeUserId()).orElse(null);

        audit.record(
            AuthAuditService.EventType.AUTHORITY_REVOKED,
            actor.getId(), actor.getRole(),
            grantee == null ? null : grantee.getId(),
            grantee == null ? null : grantee.getEmail(),
            true, null,
            "grantId=" + fresh.getId() + ", reason=" + safeReason);

        return toDto(fresh, grantee, /* grantor lookup omitted for revoke */ null, actor);
    }

    // ──────────────────────────────────────────────────────────
    // GRANTS — read
    // ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AuthorityGrantDto> listAllGrants(Pageable pageable) {
        return grantRepo.findAllByOrderByGrantedAtDesc(pageable).map(this::hydrate);
    }

    @Transactional(readOnly = true)
    public Page<AuthorityGrantDto> listActiveGrants(Pageable pageable) {
        return grantRepo.findAllActive(LocalDateTime.now(ZoneOffset.UTC), pageable).map(this::hydrate);
    }

    @Transactional(readOnly = true)
    public List<AuthorityGrantDto> listGrantsForUser(Long userId) {
        return grantRepo.findByGranteeUserIdOrderByGrantedAtDesc(userId).stream()
            .map(this::hydrate)
            .collect(Collectors.toList());
    }

    /**
     * Resolve the effective authority of {@code userId} right now. Source-of-truth used
     * by:
     * <ul>
     *   <li>Frontend rendering of the Authority Handover banner and per-page guards.</li>
     *   <li>JWT issuance — auth-service stamps the JWT's role/scopes from this result.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public EffectiveAuthorityDto getEffectiveAuthority(Long userId) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        boolean nativeSuper = user.getRole() == UserRole.SUPER_ADMIN;
        List<AuthorityGrant> active = grantRepo.findActiveForUser(userId, LocalDateTime.now(ZoneOffset.UTC));

        Set<AuthorityScope> union = active.stream()
            .flatMap(g -> g.getScopes().stream())
            .collect(Collectors.toCollection(HashSet::new));

        LocalDateTime nextExpiry = active.stream()
            .map(AuthorityGrant::getExpiresAt)
            .min(Comparator.naturalOrder())
            .orElse(null);

        return EffectiveAuthorityDto.builder()
            .role(user.getRole().name())
            .superAdmin(nativeSuper)
            .delegated(!nativeSuper && !active.isEmpty())
            .scopes(union)
            .nextExpiryAt(nextExpiry)
            .build();
    }

    /**
     * @return the union of native role + active grants for the user. Used by JWT issuer.
     */
    @Transactional(readOnly = true)
    public Set<AuthorityScope> getActiveScopesForUser(Long userId) {
        return grantRepo.findActiveForUser(userId, LocalDateTime.now(ZoneOffset.UTC)).stream()
            .flatMap(g -> g.getScopes().stream())
            .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * @return epoch-millis of the earliest expiry across the user's active grants, or 0
     *         if the user has no active grants. Used by the JWT issuer so the gateway
     *         can refuse to honour the {@code delegatedScopes} claim once the grant
     *         has expired, even if the JWT is still otherwise valid.
     */
    @Transactional(readOnly = true)
    public long getEarliestGrantExpiryEpochMillis(Long userId) {
        return grantRepo.findActiveForUser(userId, LocalDateTime.now(ZoneOffset.UTC)).stream()
            .map(AuthorityGrant::getExpiresAt)
            .min(LocalDateTime::compareTo)
            .map(t -> t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            .orElse(0L);
    }

    // ──────────────────────────────────────────────────────────
    // LOCKS
    // ──────────────────────────────────────────────────────────

    @Transactional
    public ResourceLockDto createLock(Long actingSuperAdminId, CreateResourceLockRequest req) {
        User actor = userRepo.findById(actingSuperAdminId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", actingSuperAdminId));
        if (actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new BusinessException("Only super-admins may place resource locks");
        }
        String type = req.getResourceType().trim();
        String id   = req.getResourceId().trim();

        Optional<ResourceLock> existing = lockRepo.findByResourceTypeAndResourceId(type, id);
        if (existing.isPresent()) {
            ResourceLock prev = existing.get();
            if (Objects.equals(prev.getLockedBy(), actor.getId())) {
                // Idempotent re-lock by the same owner — refresh reason if changed.
                if (!prev.getReason().equals(req.getReason().trim())) {
                    prev.setReason(req.getReason().trim());
                    prev = lockRepo.save(prev);
                }
                return toDto(prev, actor);
            }
            // Cross-owner re-lock attempt: reject. The other super-admin must release first.
            throw new BusinessException(
                "Resource is already locked by user #" + prev.getLockedBy()
                + ". Ask them to release it before re-locking.");
        }

        ResourceLock lock = ResourceLock.builder()
            .resourceType(type)
            .resourceId(id)
            .lockedBy(actor.getId())
            .lockedByName(actor.getFirstName() + " " + actor.getLastName())
            .reason(req.getReason().trim())
            .build();
        lock = lockRepo.save(lock);

        audit.record(
            AuthAuditService.EventType.RESOURCE_LOCKED,
            actor.getId(), actor.getRole(),
            null, null, true, null,
            "resourceType=" + type + ", resourceId=" + id + ", reason=" + lock.getReason());

        return toDto(lock, actor);
    }

    @Transactional
    public void releaseLock(Long actingUserId, Long lockId, String reason) {
        User actor = userRepo.findById(actingUserId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", actingUserId));
        ResourceLock lock = lockRepo.findById(lockId)
            .orElseThrow(() -> new ResourceNotFoundException("ResourceLock", "id", lockId));

        boolean nativeSuper = actor.getRole() == UserRole.SUPER_ADMIN;
        boolean isOwner = Objects.equals(lock.getLockedBy(), actor.getId());
        if (!nativeSuper && !isOwner) {
            throw new BusinessException("Only a super-admin or the lock owner may release this lock");
        }
        // A delegated admin (non-native super-admin) acting as super-admin cannot release
        // a lock they don't own. This is the lock's purpose: super-admin protection
        // against delegated admins.
        // (We rely on the fact that delegated admins reach this endpoint only when
        // they have the SUPER_DASHBOARD scope, but their role on the JWT is still
        // ADMIN at the user-record level. Native super-admin = role on the User row.)

        lockRepo.delete(lock);

        audit.record(
            AuthAuditService.EventType.RESOURCE_UNLOCKED,
            actor.getId(), actor.getRole(),
            null, null, true, null,
            "resourceType=" + lock.getResourceType()
                + ", resourceId=" + lock.getResourceId()
                + ", originalOwner=" + lock.getLockedBy()
                + ", reason=" + (reason == null ? "" : reason.trim()));
    }

    @Transactional(readOnly = true)
    public Optional<ResourceLockDto> findLock(String resourceType, String resourceId) {
        return lockRepo.findByResourceTypeAndResourceId(resourceType.trim(), resourceId.trim())
            .map(this::hydrate);
    }

    @Transactional(readOnly = true)
    public List<ResourceLockDto> listLocksByType(String resourceType) {
        return lockRepo.findByResourceType(resourceType.trim()).stream()
            .map(this::hydrate)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ResourceLockDto> listAllLocks(Pageable pageable) {
        return lockRepo.findAllByOrderByLockedAtDesc(pageable).map(this::hydrate);
    }

    // ──────────────────────────────────────────────────────────
    // Hydration helpers (denormalise user info for read DTOs)
    // ──────────────────────────────────────────────────────────

    private AuthorityGrantDto hydrate(AuthorityGrant g) {
        User grantee = userRepo.findById(g.getGranteeUserId()).orElse(null);
        User grantor = userRepo.findById(g.getGrantedBy()).orElse(null);
        User revoker = g.getRevokedBy() == null ? null
            : userRepo.findById(g.getRevokedBy()).orElse(null);
        return toDto(g, grantee, grantor, revoker);
    }

    private AuthorityGrantDto toDto(AuthorityGrant g, User grantee, User grantor, User revoker) {
        return AuthorityGrantDto.builder()
            .id(g.getId())
            .granteeUserId(g.getGranteeUserId())
            .granteeEmail(grantee == null ? null : grantee.getEmail())
            .granteeName(grantee == null ? null : (grantee.getFirstName() + " " + grantee.getLastName()))
            .scopes(new HashSet<>(g.getScopes()))
            .grantedBy(g.getGrantedBy())
            .grantedByEmail(grantor == null ? null : grantor.getEmail())
            .grantedByName(grantor == null ? null : (grantor.getFirstName() + " " + grantor.getLastName()))
            .reason(g.getReason())
            .grantedAt(g.getGrantedAt())
            .expiresAt(g.getExpiresAt())
            .revokedAt(g.getRevokedAt())
            .revokedBy(g.getRevokedBy())
            .revokedByEmail(revoker == null ? null : revoker.getEmail())
            .revokeReason(g.getRevokeReason())
            .active(g.isActive())
            .build();
    }

    private ResourceLockDto hydrate(ResourceLock l) {
        User owner = userRepo.findById(l.getLockedBy()).orElse(null);
        return toDto(l, owner);
    }

    private ResourceLockDto toDto(ResourceLock l, User owner) {
        return ResourceLockDto.builder()
            .id(l.getId())
            .resourceType(l.getResourceType())
            .resourceId(l.getResourceId())
            .lockedBy(l.getLockedBy())
            .lockedByName(l.getLockedByName() != null ? l.getLockedByName()
                : (owner == null ? null : (owner.getFirstName() + " " + owner.getLastName())))
            .lockedByEmail(owner == null ? null : owner.getEmail())
            .reason(l.getReason())
            .lockedAt(l.getLockedAt())
            .build();
    }
}
