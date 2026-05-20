package com.skbingegalaxy.booking.service.statemachine;

import lombok.Builder;
import lombok.Value;

/**
 * Identifies the actor responsible for triggering a state transition. Used by
 * {@link BookingStateMachine} to enforce role-based transition rules and to
 * populate audit trail fields (triggeredBy / triggeredByRole / triggeredByName).
 *
 * <p>Construct via the static factories so callers don't have to remember the
 * canonical role string format ({@code SYSTEM} / {@code CUSTOMER} /
 * {@code ADMIN} / {@code SUPER_ADMIN}). Roles are case-sensitive in the rule
 * table; use these factories to stay safe.
 */
@Value
@Builder
public class TransitionActor {

    String role;            // SYSTEM | CUSTOMER | ADMIN | SUPER_ADMIN
    Long userId;            // null for SYSTEM
    String displayName;     // human-readable label snapshotted into audit log; nullable

    public static final String ROLE_SYSTEM      = "SYSTEM";
    public static final String ROLE_CUSTOMER    = "CUSTOMER";
    public static final String ROLE_ADMIN       = "ADMIN";
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    public static TransitionActor system() {
        return TransitionActor.builder().role(ROLE_SYSTEM).build();
    }

    public static TransitionActor customer(Long userId, String displayName) {
        return TransitionActor.builder()
            .role(ROLE_CUSTOMER).userId(userId).displayName(displayName).build();
    }

    public static TransitionActor admin(Long userId, String displayName) {
        return TransitionActor.builder()
            .role(ROLE_ADMIN).userId(userId).displayName(displayName).build();
    }

    public static TransitionActor superAdmin(Long userId, String displayName) {
        return TransitionActor.builder()
            .role(ROLE_SUPER_ADMIN).userId(userId).displayName(displayName).build();
    }

    /**
     * Resolves an arbitrary role string from upstream HTTP headers
     * ({@code X-User-Role}) into a {@code TransitionActor}. Unknown / blank
     * roles fall back to {@link #ROLE_SYSTEM} so we never reject a legitimate
     * payment webhook because of a missing header.
     */
    public static TransitionActor from(String role, Long userId, String displayName) {
        if (role == null || role.isBlank()) return system();
        String normalized = role.trim().toUpperCase();
        return TransitionActor.builder()
            .role(normalized).userId(userId).displayName(displayName).build();
    }

    public boolean isSuperAdmin() {
        return ROLE_SUPER_ADMIN.equals(role);
    }
}
