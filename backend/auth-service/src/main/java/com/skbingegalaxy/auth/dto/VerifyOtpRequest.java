package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyOtpRequest {
    @NotBlank(message = "Reset token is required")
    private String token;

    @NotBlank(message = "OTP is required")
    @Size(min = 6, max = 8)
    private String otp;

    @NotBlank(message = "New password is required")
    @Size(min = 10, max = 100, message = "Password must be between 10 and 100 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#])[A-Za-z\\d@$!%*?&#.\\-_~+^]{10,}$",
            message = "Password must contain at least one uppercase, one lowercase, one digit and one special character")
    private String newPassword;
}
