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
     * Recovery codes issued alongside the TOTP secret at enrolment. The client must echo
     * them back here (after showing them to the user) so the server can persist their
     * hashes. Preserves the invariant that the plaintext codes never round-trip through
     * server storage — they exist only in email/hand for the user.
     */
    private List<String> recoveryCodes;
}
