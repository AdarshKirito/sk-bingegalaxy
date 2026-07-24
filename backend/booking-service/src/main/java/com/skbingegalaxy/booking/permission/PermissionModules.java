package com.skbingegalaxy.booking.permission;

import java.util.List;
import java.util.Map;

/**
 * Canonical catalogue of the per-binge OPTION modules a SUPER_ADMIN can
 * enable/disable/lock for the admins of a binge. These are exactly the
 * existing binge-operation menu items — no new modules are invented.
 *
 * <p>Keys are stable API identifiers; labels are what the matrix UI shows.
 * Keep in sync with the frontend {@code PERMISSION_MODULES} list.</p>
 */
public final class PermissionModules {

    private PermissionModules() {}

    public static final String REPORTS          = "REPORTS";
    public static final String MESSAGES         = "MESSAGES";
    public static final String VENUE            = "VENUE";
    public static final String ROOMS            = "ROOMS";
    public static final String EVENT_TYPES      = "EVENT_TYPES";
    public static final String RATE_CODES       = "RATE_CODES";
    public static final String SURGE_RULES      = "SURGE_RULES";
    public static final String BLOCKED_DATES    = "BLOCKED_DATES";
    public static final String SLOT_HOLDS       = "SLOT_HOLDS";
    public static final String PEOPLE           = "PEOPLE";
    public static final String USERS            = "USERS";
    public static final String WAITLIST         = "WAITLIST";
    public static final String CUSTOMER_FREEZES = "CUSTOMER_FREEZES";
    public static final String RISK_FLAGS       = "RISK_FLAGS";
    public static final String SUPPORT_CONSOLE  = "SUPPORT_CONSOLE";
    public static final String DISPUTES         = "DISPUTES";
    public static final String FAILED_REFUNDS   = "FAILED_REFUNDS";

    /** Ordered as the admin menu shows them. */
    public static final List<String> ALL = List.of(
        REPORTS, MESSAGES, VENUE, ROOMS, EVENT_TYPES, RATE_CODES, SURGE_RULES,
        BLOCKED_DATES, SLOT_HOLDS, PEOPLE, USERS, WAITLIST, CUSTOMER_FREEZES,
        RISK_FLAGS, SUPPORT_CONSOLE, DISPUTES, FAILED_REFUNDS);

    /**
     * Modules whose misuse has direct financial/abuse blast radius. A future
     * Venue Manager may only receive these if BOTH the binge admin and the
     * super-admin allowed them (parent-rule), per the access-control spec.
     */
    public static final List<String> SENSITIVE = List.of(
        RATE_CODES, SURGE_RULES, CUSTOMER_FREEZES, RISK_FLAGS, DISPUTES, FAILED_REFUNDS);

    public static boolean isValid(String key) {
        return key != null && ALL.contains(key);
    }

    /**
     * Booking-service URL fragment → module key, used by the enforcement
     * interceptor. First match wins; fragments are chosen to be distinctive so
     * unrelated admin paths (e.g. the Binges list itself) are never blocked.
     * DISPUTES / FAILED_REFUNDS / BLOCKED_DATES are enforced inside
     * payment-service and availability-service respectively (they own those
     * URLs) via the internal denied-modules lookup.
     */
    public static final List<Map.Entry<String, String>> MODULE_BY_PATH = List.of(
        Map.entry("/admin/reports", REPORTS),
        Map.entry("/admin/export", REPORTS),
        Map.entry("/admin/venue-rooms", ROOMS),
        Map.entry("/admin/event-types", EVENT_TYPES),
        Map.entry("/admin/add-ons", EVENT_TYPES),
        // Surge lives under /admin/pricing/surge-rules — must map BEFORE the
        // broader /admin/pricing prefix (first match wins).
        Map.entry("/pricing/surge-rules", SURGE_RULES),
        Map.entry("/admin/pricing", RATE_CODES),
        Map.entry("/slot-holds/admin", SLOT_HOLDS),
        Map.entry("/waitlist/admin", WAITLIST),
        Map.entry("/admin/freezes", CUSTOMER_FREEZES),
        Map.entry("/admin/risk-flags", RISK_FLAGS),
        Map.entry("/admin/support", SUPPORT_CONSOLE),
        // NOTE: /admin/notifications is deliberately NOT mapped to MESSAGES.
        // The admin notification bell + inbox is a GLOBAL, identity-scoped
        // surface (every query is filtered by the caller's own X-User-Id) shown
        // on the entrance dashboard BEFORE any binge is selected — so it can
        // never carry an X-Binge-Id, and the module interceptor's fail-closed
        // "missing binge context" rule would 403 every admin's bell on every
        // page load (super-admins were exempt, which is why only admins broke).
        // Messaging here is not per-binge, so it does not belong in the
        // per-binge matrix; the MESSAGES toggle governs the Messages MENU item
        // on the client. Receiving system/approval notifications must never be
        // blockable — an admin locked out of their own inbox can't operate.
        Map.entry("/admin/customers", USERS),
        // Venue content editing (customer dashboard / about experience, media).
        Map.entry("/customer-dashboard", VENUE),
        Map.entry("/customer-about", VENUE),
        Map.entry("/admin/media", VENUE)
    );

    /** Resolve the module for a request URI, or null when the path is unmapped. */
    public static String resolveModule(String uri) {
        if (uri == null) return null;
        for (Map.Entry<String, String> e : MODULE_BY_PATH) {
            if (uri.contains(e.getKey())) return e.getValue();
        }
        return null;
    }
}
