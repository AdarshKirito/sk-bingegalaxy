package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    /** Optional 6-digit TOTP code or formatted recovery code for MFA-enabled accounts. */
    @Size(max = 32, message = "MFA code is too long")
    private String mfaCode;
}
