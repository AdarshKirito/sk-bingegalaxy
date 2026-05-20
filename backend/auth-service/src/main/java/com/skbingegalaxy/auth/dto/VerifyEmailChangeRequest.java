package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Step-2 of the email-change flow: the user supplies the OTP (and optionally
 * the token sent in the verification email link) to confirm the new address.
 */
@Data
public class VerifyEmailChangeRequest {

    /** 6-digit one-time code sent to the new email address. */
    @NotBlank(message = "Verification code is required")
    private String otp;

    /**
     * Token from the verification link (optional; allows link-based confirmation
     * in addition to OTP entry). When provided it is verified against the
     * SHA-256 hash stored in the database.
     */
    private String token;
}
