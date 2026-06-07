package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.HttpAuthorityLockClient;
import com.skbingegalaxy.booking.client.HttpAuthorityLockClient.ResourceLockSummary;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Authority Handover — capability lock enforcement for binge-scoped admin actions.
 *
 * <p>A native super-admin can lock a <em>capability</em> (e.g. {@code PRICING},
 * {@code EVENT_TYPES}, {@code TAXES}) either for one binge or for every binge, so the
 * binge's own admin can no longer change it. This guard is the server-side enforcement:
 * call {@link #requireUnlocked(String, String)} at the start of every admin <b>mutation</b>
 * (create/update/delete) for a lockable capability. Reads are never blocked.
 *
 * <p>Lock identity reuses the generic {@code (resourceType, resourceId)} model:
 * <ul>
 *   <li>{@code resourceType} = the capability token (e.g. {@code "PRICING"}).</li>
 *   <li>{@code resourceId}   = the binge id, or the wildcard {@code "ALL"} to lock the
 *       capability across every binge.</li>
 * </ul>
 * A binge-specific lock and an ALL-binges lock are both honoured (binge first, then ALL).
 *
 * <p><b>Who is blocked:</b> regular binge admins (role {@code ADMIN}). Native
 * super-admins always pass — they own the locks and need a path to edit/release them.
 *
 * <p><b>Failure mode:</b> if auth-service is unreachable the lock client returns
 * {@code null} and we fail-open (treat as unlocked) so a transient outage never freezes
 * the whole admin console; locks are rare and the UI badge is a secondary cue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorityLockGuard {

    /** Sentinel resourceId meaning "every binge". */
    public static final String ALL_BINGES = "ALL";

    private final HttpAuthorityLockClient lockClient;

    private static boolean isSuperAdmin(String role) {
        return "SUPER_ADMIN".equalsIgnoreCase(role) || "ROLE_SUPER_ADMIN".equalsIgnoreCase(role);
    }

    /**
     * Throw {@code 423 Locked} if {@code capability} is locked for the currently selected
     * binge (or for all binges) and the caller is not a native super-admin.
     *
     * @param capability capability token, e.g. {@code "PRICING"}
     * @param role       caller role from {@code X-User-Role}
     */
    public void requireUnlocked(String capability, String role) {
        // Native super-admins can always change — they own and release the locks.
        if (isSuperAdmin(role)) return;

        Long bingeId = BingeContext.getBingeId();
        ResourceLockSummary lock = bingeId != null
            ? lockClient.lookup(capability, String.valueOf(bingeId))
            : null;
        if (lock == null) {
            lock = lockClient.lookup(capability, ALL_BINGES);
        }
        if (lock == null) return;

        String owner = lock.getLockedByName() != null ? lock.getLockedByName() : "a super-admin";
        log.info("authority.lock.block capability={} binge={} adminBlocked=true reason={}",
            capability, bingeId, lock.getReason());
        throw new BusinessException(
            "\"" + capability + "\" is locked by " + owner
                + (lock.getReason() != null ? " — " + lock.getReason() : "")
                + ". Ask them to release it via the Authority Handover console.",
            HttpStatus.LOCKED);
    }
}
