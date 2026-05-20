package com.skbingegalaxy.auth.dto;

import com.skbingegalaxy.common.enums.AuthorityScope;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Request body for {@code POST /api/v1/auth/authority/grants}.
 *
 * <p>The {@code reason} field is mandatory by design — every privileged elevation must
 * carry a written justification that lands in the audit log. The {@code durationHours}
 * field defaults to 4 (industry standard for JIT) and is server-clamped to a maximum
 * of 24 (any value &gt; 24 is rejected with 400).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAuthorityGrantRequest {

    @NotNull(message = "granteeUserId is required")
    @Positive(message = "granteeUserId must be positive")
    private Long granteeUserId;

    @NotEmpty(message = "scopes must not be empty")
    @Size(max = 20, message = "scopes can contain at most 20 entries")
    private Set<AuthorityScope> scopes;

    @NotBlank(message = "reason is required (audit requirement)")
    @Size(min = 8, max = 500, message = "reason must be 8-500 characters")
    private String reason;

    /**
     * Server clamps to [1, 24]. Defaults to 4 when null. Anything outside the range
     * is rejected; we intentionally do not silently coerce so a typo doesn't grant
     * 24h when the operator meant 2.
     */
    @Min(value = 1, message = "durationHours must be ≥ 1")
    @Max(value = 24, message = "durationHours must be ≤ 24 (policy)")
    private Integer durationHours;
}
