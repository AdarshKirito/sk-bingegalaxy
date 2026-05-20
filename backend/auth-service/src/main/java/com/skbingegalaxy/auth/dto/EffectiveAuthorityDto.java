package com.skbingegalaxy.auth.dto;

import com.skbingegalaxy.common.enums.AuthorityScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Returned by {@code GET /api/v1/auth/authority/me}. Lets the frontend render the
 * Authority Handover UI accurately for the current viewer:
 * <ul>
 *   <li>Native super-admins see the full management UI.</li>
 *   <li>Delegated admins see a banner: "Acting as super-admin in N scope(s) until …"
 *       and only those scopes' pages are reachable.</li>
 *   <li>Regular admins see no super-admin pages.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EffectiveAuthorityDto {
    /** Native role from the user record (CUSTOMER / ADMIN / SUPER_ADMIN). */
    private String role;
    /** True iff role == SUPER_ADMIN (no grant needed). */
    private boolean superAdmin;
    /** True iff there is at least one active grant — i.e. acting as super-admin via delegation. */
    private boolean delegated;
    /** Union of all currently granted scopes (empty if none). */
    private Set<AuthorityScope> scopes;
    /** Earliest expiry across active grants, so UI can show a single countdown. */
    private LocalDateTime nextExpiryAt;
}
