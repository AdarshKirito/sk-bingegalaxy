package com.skbingegalaxy.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Checks candidate passwords against the HaveIBeenPwned k-anonymity API.
 *
 * <h3>How k-anonymity works</h3>
 * <ol>
 *   <li>SHA-1 hash the candidate password.</li>
 *   <li>Send only the first 5 hex characters to the HIBP API — the server never
 *       sees the full hash (or the password).</li>
 *   <li>The API returns all known suffix matches. Check if our full hash suffix
 *       appears in the response with a count > 0.</li>
 *   <li>If found: the password appears in a data breach — reject registration.</li>
 * </ol>
 *
 * <p>The check is skipped if {@code app.security.check-pwned-passwords=false} (dev default).
 * In production it is enforced. Network failure is NOT treated as a rejection — the
 * check degrades gracefully so a HIBP API outage doesn't block all registrations.
 *
 * <p>Reference: https://haveibeenpwned.com/API/v3#SearchingPwnedPasswordsByRange
 */
@Service
@Slf4j
public class PwnedPasswordService {

    private static final String HIBP_URL = "https://api.pwnedpasswords.com/range/";

    @Value("${app.security.check-pwned-passwords:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;

    public PwnedPasswordService(RestTemplateBuilder builder) {
        this.restTemplate = builder
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Returns {@code true} if the password appears in any known data breach.
     * Returns {@code false} on API error — breach checking degrades gracefully.
     */
    public boolean isPasswordPwned(String plainPassword) {
        if (!enabled || plainPassword == null || plainPassword.isBlank()) {
            return false;
        }
        try {
            String sha1 = sha1Hex(plainPassword).toUpperCase();
            String prefix = sha1.substring(0, 5);
            String suffix = sha1.substring(5);

            String response = restTemplate.getForObject(HIBP_URL + prefix, String.class);
            if (response == null) return false;

            // Response: one "SUFFIX:COUNT" per line — check if our suffix is present with count > 0
            for (String line : response.split("\r?\n")) {
                String[] parts = line.split(":");
                if (parts.length == 2 && parts[0].equalsIgnoreCase(suffix)) {
                    long count = Long.parseLong(parts[1].trim());
                    if (count > 0) {
                        log.info("Pwned password check: password found in {} breach record(s) — rejecting",
                            count);
                        return true;
                    }
                }
            }
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-1 not available — breach check skipped", e);
        } catch (RestClientException e) {
            // HIBP API is unreachable — fail open to not block all registrations
            log.warn("HIBP API unreachable ({}) — breach check skipped, allowing password", e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error in breach check — allowing password: {}", e.getMessage());
        }
        return false;
    }

    private String sha1Hex(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(40);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
