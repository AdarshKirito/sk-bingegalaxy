package com.skbingegalaxy.booking.permission;

import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-binge module access control.
 *
 * <p>Hierarchy rule (spec): a lower level can never grant more than its
 * parent. Concretely today: SUPER_ADMIN decides what each binge ADMIN may use
 * per binge (the "Super Admin override"); an ADMIN may later manage Venue
 * Manager / Staff rows inside their own binge, but can never enable a module
 * the super-admin disabled or locked for that binge's admin — {@link
 * #assertParentAllows} enforces exactly that and is already wired for the
 * future roles.</p>
 *
 * <p>Enforcement reads go through a 30-second in-process TTL cache so the
 * interceptor adds no per-request DB round trip in the steady state; every
 * write invalidates the binge's cache entry.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BingeModulePermissionService {

    /** How long a denied-modules snapshot may be served before re-reading. */
    private static final long CACHE_TTL_MS = 30_000L;

    private final BingeModulePermissionRepository permissionRepository;
    private final BingePermissionAuditRepository auditRepository;

    /** (bingeId:userId) → [expiryEpochMs, Set of denied moduleKeys]. */
    private final ConcurrentHashMap<String, CacheEntry> deniedCache = new ConcurrentHashMap<>();

    private record CacheEntry(long expiresAt, java.util.Set<String> denied) {}

    // ── Enforcement reads ────────────────────────────────────────────────

    /** Module keys the user may NOT use in this binge (locked or disabled). */
    public java.util.Set<String> deniedModules(Long bingeId, Long userId) {
        if (bingeId == null || userId == null) return java.util.Set.of();
        String key = bingeId + ":" + userId;
        long now = System.currentTimeMillis();
        CacheEntry cached = deniedCache.get(key);
        if (cached != null && cached.expiresAt() > now) return cached.denied();

        java.util.Set<String> denied = new java.util.HashSet<>();
        for (BingeModulePermission p : permissionRepository.findByBingeIdAndUserId(bingeId, userId)) {
            if ("ALL".equals(p.getActionKey()) && !p.isAllowed()) {
                denied.add(p.getModuleKey());
            }
        }
        java.util.Set<String> frozen = java.util.Set.copyOf(denied);
        deniedCache.put(key, new CacheEntry(now + CACHE_TTL_MS, frozen));
        return frozen;
    }

    public boolean isDenied(Long bingeId, Long userId, String moduleKey) {
        return deniedModules(bingeId, userId).contains(moduleKey);
    }

    // ── Matrix reads (management UIs) ────────────────────────────────────

    /** Every explicit permission row for a binge (matrix view). */
    public List<BingeModulePermission> matrix(Long bingeId) {
        return permissionRepository.findByBingeId(bingeId);
    }

    public List<BingePermissionAudit> recentAudit(Long bingeId) {
        return auditRepository.findTop50ByBingeIdOrderByCreatedAtDesc(bingeId);
    }

    // ── Writes ───────────────────────────────────────────────────────────

    /**
     * Create/update one decision. Rules enforced here:
     * <ul>
     *   <li>SUPER_ADMIN may set anything, including {@code locked}.</li>
     *   <li>ADMIN (future: managing Venue Manager / Staff rows in their own
     *       binge) may never set {@code locked}, may never touch their own
     *       row, and may not enable a module their own access has disabled
     *       or locked (parent rule).</li>
     * </ul>
     */
    @Transactional
    public BingeModulePermission setPermission(Long bingeId, Long targetUserId, String targetRole,
                                               String moduleKey, boolean enabled, boolean locked,
                                               String remarks, Long actorId, String actorRole) {
        if (!PermissionModules.isValid(moduleKey)) {
            throw new BusinessException("Unknown module '" + moduleKey + "'", HttpStatus.BAD_REQUEST);
        }
        boolean actorIsSuper = "SUPER_ADMIN".equalsIgnoreCase(actorRole);
        if (!actorIsSuper) {
            if (locked) {
                throw new BusinessException("Only a super-admin can lock a module", HttpStatus.FORBIDDEN);
            }
            if (Objects.equals(actorId, targetUserId)) {
                throw new BusinessException("You cannot change your own permissions", HttpStatus.FORBIDDEN);
            }
            if (enabled) {
                assertParentAllows(bingeId, actorId, moduleKey);
            }
        }

        BingeModulePermission row = permissionRepository
            .findByBingeIdAndUserIdAndModuleKeyAndActionKey(bingeId, targetUserId, moduleKey, "ALL")
            .orElse(null);

        Boolean oldEnabled = row != null ? row.isEnabled() : null;
        Boolean oldLocked = row != null ? row.isLockedBySuperAdmin() : null;

        // A standing super-admin lock can only be altered by a super-admin.
        if (row != null && row.isLockedBySuperAdmin() && !actorIsSuper) {
            throw new BusinessException(
                "This option is locked by the Super Admin and cannot be changed", HttpStatus.FORBIDDEN);
        }

        if (row == null) {
            row = BingeModulePermission.builder()
                .bingeId(bingeId)
                .userId(targetUserId)
                .role(targetRole != null ? targetRole : "ADMIN")
                .moduleKey(moduleKey)
                .actionKey("ALL")
                .build();
        }
        row.setEnabled(enabled);
        row.setLockedBySuperAdmin(locked);
        row.setGrantedByUserId(actorId);
        row.setGrantedByRole(actorRole);
        row.setRemarks(trim(remarks));
        row = permissionRepository.save(row);

        auditRepository.save(BingePermissionAudit.builder()
            .bingeId(bingeId)
            .targetUserId(targetUserId)
            .moduleKey(moduleKey)
            .actionKey("ALL")
            .oldEnabled(oldEnabled)
            .newEnabled(enabled)
            .oldLocked(oldLocked)
            .newLocked(locked)
            .changedByUserId(actorId)
            .changedByRole(actorRole)
            .remarks(trim(remarks))
            .build());

        evict(bingeId, targetUserId);
        log.info("Permission set: binge={} user={} module={} enabled={} locked={} by {} ({})",
            bingeId, targetUserId, moduleKey, enabled, locked, actorId, actorRole);
        return row;
    }

    /**
     * Approval-time setup: the super-admin decides which modules the owning
     * admin starts with. Only DISABLED modules get rows (deny-list model);
     * previously-disabled modules missing from the new list are re-enabled.
     */
    @Transactional
    public void applyModuleSelection(Long bingeId, Long adminUserId, List<String> disabledModules,
                                     String remarks, Long actorId, String actorRole) {
        java.util.Set<String> toDisable = new java.util.HashSet<>(
            disabledModules == null ? List.of() : disabledModules);
        for (String key : toDisable) {
            if (!PermissionModules.isValid(key)) {
                throw new BusinessException("Unknown module '" + key + "'", HttpStatus.BAD_REQUEST);
            }
        }
        // Disable the selected ones…
        for (String key : toDisable) {
            setPermission(bingeId, adminUserId, "ADMIN", key, false, false, remarks, actorId, actorRole);
        }
        // …and re-enable anything previously disabled that is no longer selected.
        for (BingeModulePermission p : permissionRepository.findByBingeIdAndUserId(bingeId, adminUserId)) {
            if ("ALL".equals(p.getActionKey()) && !p.isAllowed() && !toDisable.contains(p.getModuleKey())) {
                setPermission(bingeId, adminUserId, p.getRole(), p.getModuleKey(),
                    true, false, remarks, actorId, actorRole);
            }
        }
    }

    /**
     * Parent rule: an actor may only grant a module they themselves are
     * allowed to use in this binge. Locked modules fail with the dedicated
     * "locked by Super Admin" message so the UI can show the standard copy.
     */
    private void assertParentAllows(Long bingeId, Long actorId, String moduleKey) {
        BingeModulePermission own = permissionRepository
            .findByBingeIdAndUserIdAndModuleKeyAndActionKey(bingeId, actorId, moduleKey, "ALL")
            .orElse(null);
        if (own == null || own.isAllowed()) return;
        throw new BusinessException(own.isLockedBySuperAdmin()
                ? "This option is locked by the Super Admin — you cannot grant it to anyone"
                : "You cannot grant an option that is disabled for you",
            HttpStatus.FORBIDDEN);
    }

    private void evict(Long bingeId, Long userId) {
        deniedCache.remove(bingeId + ":" + userId);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > 500 ? t.substring(0, 500) : t;
    }
}
