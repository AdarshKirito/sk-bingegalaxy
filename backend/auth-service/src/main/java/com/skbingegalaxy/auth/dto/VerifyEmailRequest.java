package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Supports two verification flows:
 *   <ul>
 *     <li>OTP: client sends {@code email} + {@code otp} (6 digits)</li>
 *     <li>Link: client sends {@code token} (opaque), no email/otp required</li>
 *   </ul>
 * The controller dispatches based on whether {@code token} is present.
 */
@Data
public class VerifyEmailRequest {

    @Email
    private String email;

    @Size(min = 6, max = 6, message = "Verification code must be 6 digits")
    private String otp;

    /** Opaque verification token from the emailed link. Mutually exclusive with otp. */
    @Size(max = 128)
    private String token;
}
