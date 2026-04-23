package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MfaCodeRequest {
    @NotBlank(message = "Verification code is required")
    @Size(min = 6, max = 32)
    private String code;
}
