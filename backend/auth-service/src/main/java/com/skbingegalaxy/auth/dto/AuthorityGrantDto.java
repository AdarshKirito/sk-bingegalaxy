package com.skbingegalaxy.auth.dto;

import com.skbingegalaxy.common.enums.AuthorityScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Read DTO for an {@code AuthorityGrant} row. We deliberately surface the grantee's
 * email/name (denormalised at read time) so the super-admin UI never has to do an N+1
 * lookup against the user repo. Sensitive PII (phone, etc) stays server-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorityGrantDto {
    private Long id;
    private Long granteeUserId;
    private String granteeEmail;
    private String granteeName;
    private Set<AuthorityScope> scopes;

    private Long grantedBy;
    private String grantedByEmail;
    private String grantedByName;

    private String reason;
    private LocalDateTime grantedAt;
    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;
    private Long revokedBy;
    private String revokedByEmail;
    private String revokeReason;

    /** Convenience boolean for the UI; computed server-side at read time. */
    private boolean active;
}
