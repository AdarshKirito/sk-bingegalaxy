package com.skbingegalaxy.auth.service;

public interface CaptchaValidationService {
    /**
     * Validates a CAPTCHA response token supplied by the client.
     *
     * @param token the reCAPTCHA v3 (or equivalent) token from the client
     * @return true when the token is valid and represents a real user interaction
     */
    boolean validate(String token);
}
