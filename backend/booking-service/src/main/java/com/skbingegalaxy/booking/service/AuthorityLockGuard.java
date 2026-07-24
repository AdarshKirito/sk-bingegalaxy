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

    /**
     * Capability token for the venue-timezone GRANT (note: grant polarity, not a
     * lock). Unlike every other token in this system — which are <em>locks</em>
     * (default-allow, a row means "frozen") — a {@code TIMEZONE_CHANGE} row in the
     * shared resource-lock store means "this binge's admin is PERMITTED to change
     * the venue timezone". Default is DENY: changing the zone reinterprets the
     * wall-clock of every existing booking, so it's least-privilege by default and
     * a super-admin grants it explicitly per-binge or for ALL binges. Keep this
     * value in sync with the frontend {@code TIMEZONE_CHANGE} constant.
     */
    public static final String TIMEZONE_CHANGE = "TIMEZONE_CHANGE";

    private final HttpAuthorityLockClient lockClient;

    private static boolean isSuperAdmin(String role) {
        return "SUPER_ADMIN".equalsIgnoreCase(role) || "ROLE_SUPER_ADMIN".equalsIgnoreCase(role);
    }

    /**
     * Throw {@code 423 Locked} if {@code capability} is locked for the currently selected
     * binge (or for all binges) and the caller is not a <em>native</em> super-admin.
     *
     * <p>A <b>delegated</b> admin holds effective {@code SUPER_ADMIN} via a temporary
     * Authority-Handover grant (gateway sets {@code X-User-Role=SUPER_ADMIN} +
     * {@code X-Authority-Delegated=true}), but is STILL bound by locks — that is the whole
     * point of locks vs grants: a super-admin can hand over broad authority yet fence off
     * specific capabilities. Only a native super-admin (not delegated) bypasses.
     *
     * @param capability capability token, e.g. {@code "PRICING"}
     * @param role       caller effective role from {@code X-User-Role}
     * @param delegated  {@code true} when the caller's authority is delegated (from
     *                   {@code X-Authority-Delegated})
     */
    public void requireUnlocked(String capability, String role, boolean delegated) {
        // Only a NATIVE super-admin bypasses — they own and release the locks.
        if (isSuperAdmin(role) && !delegated) return;

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

    /**
     * Default-DENY grant check for changing a venue's timezone. Throws
     * {@code 423 Locked} unless the caller is permitted.
     *
     * <p>Permitted when EITHER:
     * <ul>
     *   <li>the caller is a <b>native</b> super-admin (they own grants), or</li>
     *   <li>a {@code TIMEZONE_CHANGE} grant row exists for this {@code bingeId}
     *       <em>or</em> for {@link #ALL_BINGES}.</li>
     * </ul>
     *
     * <p><b>Why default-deny:</b> changing a venue's IANA zone retroactively
     * reinterprets the wall-clock of every existing booking (and check-in
     * windows, operational-day rollover, late-arrival), so it's a high-blast-radius
     * setting. Least-privilege: a binge admin cannot touch it until a super-admin
     * explicitly grants the capability.
     *
     * <p><b>Fail-closed:</b> the lock client returns {@code null} on an
     * auth-service outage; for a grant that means "no grant proven" → deny. Only a
     * native super-admin can change the zone while auth-service is unreachable.
     * This is the opposite posture to {@link #requireUnlocked} (which fails open)
     * and is intentional given the sensitivity.
     *
     * @param role      effective caller role ({@code X-User-Role})
     * @param delegated whether authority is delegated ({@code X-Authority-Delegated})
     * @param bingeId   the binge whose timezone is being changed (the path id, NOT
     *                  the ambient {@code BingeContext} — a super-admin may edit a
     *                  binge other than their currently selected one)
     */
    public void requireTimezoneChangePermitted(String role, boolean delegated, Long bingeId) {
        // Native super-admin always permitted — they issue and revoke the grants.
        if (isSuperAdmin(role) && !delegated) return;

        if (bingeId != null) {
            ResourceLockSummary bingeGrant = lockClient.lookup(TIMEZONE_CHANGE, String.valueOf(bingeId));
            if (bingeGrant != null) return;
        }
        ResourceLockSummary allGrant = lockClient.lookup(TIMEZONE_CHANGE, ALL_BINGES);
        if (allGrant != null) return;

        log.info("authority.timezone.denied binge={} role={} delegated={} reason=no-grant",
            bingeId, role, delegated);
        throw new BusinessException(
            "Changing the venue timezone requires super-admin permission. "
                + "Ask a super-admin to grant timezone access for this venue via the Authority Handover console.",
            HttpStatus.LOCKED);
    }
}
