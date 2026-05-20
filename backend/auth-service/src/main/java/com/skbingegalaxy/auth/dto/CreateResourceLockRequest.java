package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for {@code POST /api/v1/auth/authority/locks}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateResourceLockRequest {

    @NotBlank(message = "resourceType is required")
    @Size(max = 64)
    private String resourceType;

    @NotBlank(message = "resourceId is required")
    @Size(max = 128)
    private String resourceId;

    @NotBlank(message = "reason is required (audit requirement)")
    @Size(min = 4, max = 500)
    private String reason;
}
