package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MfaCodeRequest {
    @NotBlank(message = "Verification code is required")
    @Size(min = 6, max = 32)
    private String code;

    /**
     * Account password, used to re-authenticate before disabling MFA. Turning off
     * the second factor is a security downgrade, so a live session (stolen cookie,
     * unlocked laptop) must not by itself be enough.
     *
     * <p>Deliberately NOT {@code @NotBlank}: accounts created through federated
     * sign-in have no password the user could supply, and requiring one would make
     * 2FA impossible to disable. Whether it is required is decided per-account in
     * {@code AuthService.disableMfa}, which is the only place that can tell.
     */
    private String currentPassword;
}
