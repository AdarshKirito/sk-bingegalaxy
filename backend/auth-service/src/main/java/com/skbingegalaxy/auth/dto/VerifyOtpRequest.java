package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyOtpRequest {
    @NotBlank(message = "OTP is required")
    @Size(min = 6, max = 6)
    private String otp;

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 100)
    private String newPassword;
}
