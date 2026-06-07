package com.skbingegalaxy.auth.service.impl;

import com.skbingegalaxy.auth.service.CaptchaValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Dev/test stub — always accepts any non-blank token.
 * Wired only when spring.profiles.active does NOT include "production".
 */
@Service
@Profile("!production")
@Slf4j
public class StubCaptchaValidationService implements CaptchaValidationService {

    @Override
    public boolean validate(String token) {
        log.warn("DEV/TEST ONLY: CaptchaValidationService stub accepted token. Real reCAPTCHA is NOT being validated.");
        return token != null && !token.isBlank();
    }
}
