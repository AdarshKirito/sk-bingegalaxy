package com.skbingegalaxy.common.exception;

import org.springframework.http.HttpStatus;

public class CaptchaRequiredException extends BusinessException {
    public CaptchaRequiredException() {
        super("Too many failed login attempts. Please complete the CAPTCHA challenge before trying again.", HttpStatus.TOO_MANY_REQUESTS);
    }
}
