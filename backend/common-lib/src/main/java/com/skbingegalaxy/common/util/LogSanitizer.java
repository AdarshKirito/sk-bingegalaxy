package com.skbingegalaxy.common.util;

/**
 * Helpers to scrub personally identifiable information from log lines and
 * error responses. Never returns {@code null}; always returns a short
 * fixed-shape token that keeps log cardinality low while preserving
 * enough of the value to correlate incidents.
 *
 * <p>Log output for emails / phones / tokens is processed by every pod and
 * shipped to Loki / OpenSearch. Raw PII there would:
 * <ul>
 *   <li>violate GDPR / DPDP / CCPA data-minimisation,</li>
 *   <li>become a secondary data source that must be access-controlled,</li>
 *   <li>expand the blast radius of a stolen log-shipping credential.</li>
 * </ul>
 * Masking at the call site keeps that burden in one place and makes log
 * review safe by default.
 */
public final class LogSanitizer {

    private LogSanitizer() {}

    private static final String REDACTED = "***";

    /**
     * {@code john.doe@example.com} → {@code jo***@example.com}.
     * Preserves domain for routing diagnostics without leaking identity.
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return REDACTED;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return REDACTED;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        String head = local.length() <= 2 ? local.substring(0, Math.min(1, local.length())) : local.substring(0, 2);
        return head + REDACTED + "@" + domain;
    }

    /**
     * {@code +919876543210} → {@code +91******3210}. Shows country code +
     * last 4 digits (standard fraud-investigation tail).
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return REDACTED;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() <= 4) {
            return REDACTED;
        }
        String tail = digits.substring(digits.length() - 4);
        String head = digits.startsWith("+") ? digits.substring(0, Math.min(3, digits.length() - 4)) : "";
        return head + REDACTED + tail;
    }

    /** Show only first / last 4 chars of a token (e.g. JWT, reset token). */
    public static String maskToken(String token) {
        if (token == null || token.length() < 12) {
            return REDACTED;
        }
        return token.substring(0, 4) + REDACTED + token.substring(token.length() - 4);
    }
}
