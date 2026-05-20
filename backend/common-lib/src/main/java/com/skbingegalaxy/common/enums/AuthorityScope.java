package com.skbingegalaxy.common.enums;

/**
 * Per-page scopes used by the Authority Handover system.
 *
 * <p>A super-admin can issue a time-bounded {@code AuthorityGrant} to an admin for one
 * or more of these scopes. While the grant is active, the grantee is treated as a
 * super-admin <em>only for the granted scopes</em>; access to other super-admin
 * surfaces remains denied.</p>
 *
 * <p>Each scope corresponds to a distinct super-admin-only page in the admin UI and
 * a corresponding family of backend endpoints. Adding a new super-admin page requires
 * adding a new value here and mapping the path in the gateway scope-enforcement
 * filter — there is no implicit catch-all.</p>
 *
 * <p>Naming convention: SCREAMING_SNAKE_CASE matching the area, not the URL.</p>
 */
public enum AuthorityScope {
    /** Manage display currencies and FX rates. */
    CURRENCIES,
    /** Manage email/SMS/WhatsApp notification templates. */
    NOTIFICATIONS,
    /** Manage loyalty tiers, perks, bindings, status-match, parity. */
    LOYALTY,
    /** Operations console: outbox replays, kafka tools, danger zone. */
    OPS,
    /** All-users admin (cross-binge customer view, ban/unban, delete). */
    ALL_USERS,
    /** Edit individual customer record (PII edit). */
    CUSTOMER_EDIT,
    /** Register new admins (admin onboarding). */
    ADMIN_REGISTER,
    /** Edit the platform-wide home CMS document. */
    HOME_CMS,
    /** Edit the platform-wide /account page CMS document. */
    ACCOUNT_CMS,
    /** Super-admin dashboard (audit, sessions overview, etc). */
    SUPER_DASHBOARD;

    public static AuthorityScope fromString(String s) {
        if (s == null) return null;
        try {
            return AuthorityScope.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
