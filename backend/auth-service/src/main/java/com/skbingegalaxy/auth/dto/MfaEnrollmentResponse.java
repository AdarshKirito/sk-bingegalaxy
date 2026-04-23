package com.skbingegalaxy.auth.dto;

import java.util.List;

public record MfaEnrollmentResponse(
    String secret,
    String otpauthUri,
    List<String> recoveryCodes
) { }
