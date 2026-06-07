package com.skbingegalaxy.auth.service.impl;

import com.skbingegalaxy.auth.service.CaptchaValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * Production reCAPTCHA v3 validator.
 * Set RECAPTCHA_SECRET_KEY env var to your Google reCAPTCHA secret key.
 * Set app.recaptcha.score-threshold (default 0.5) to tune sensitivity.
 */
@Service
@Profile("production")
@Slf4j
public class RecaptchaValidationService implements CaptchaValidationService {

    private static final String VERIFY_URL =
        "https://www.google.com/recaptcha/api/siteverify?secret={secret}&response={response}";

    @Value("${app.recaptcha.secret-key}")
    private String secretKey;

    @Value("${app.recaptcha.score-threshold:0.5}")
    private double scoreThreshold;

    private final RestTemplate restTemplate;

    public RecaptchaValidationService(RestTemplateBuilder builder) {
        this.restTemplate = builder
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean validate(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> response = restTemplate.getForObject(
                VERIFY_URL, Map.class, secretKey, token);
            if (response == null) {
                log.warn("reCAPTCHA API returned null response");
                return false;
            }
            Boolean success = (Boolean) response.get("success");
            if (!Boolean.TRUE.equals(success)) {
                log.warn("reCAPTCHA verification failed: error-codes={}", response.get("error-codes"));
                return false;
            }
            // For reCAPTCHA v3, also check the score
            Object scoreObj = response.get("score");
            if (scoreObj instanceof Number scoreNum) {
                double score = scoreNum.doubleValue();
                if (score < scoreThreshold) {
                    log.warn("reCAPTCHA score too low: score={} threshold={}", score, scoreThreshold);
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            log.error("reCAPTCHA validation request failed: {}", ex.getMessage());
            // Fail-open only in edge cases — prefer fail-closed in production
            return false;
        }
    }
}
