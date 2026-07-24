package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MfaConfirmRequest {

    @NotBlank(message = "Verification code is required")
    @Size(min = 6, max = 8, message = "Invalid verification code format")
    private String code;

    /**
     * @deprecated IGNORED by the server since the V20 MFA hardening. Recovery codes are
     * generated, hashed and stored server-side at {@code /mfa/enroll}. Accepting them
     * from the client meant whatever it sent became the account's recovery codes, so an
     * XSS or MITM could plant a known set and retain permanent access. Retained only so
     * that older clients still POST a body the server accepts; remove once they are gone.
     */
    @Deprecated
    private List<String> recoveryCodes;
}
