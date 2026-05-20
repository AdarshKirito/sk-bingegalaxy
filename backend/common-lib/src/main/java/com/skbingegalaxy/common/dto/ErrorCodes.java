package com.skbingegalaxy.common.dto;

/**
 * Stable machine-readable error identifiers shipped in {@link ApiResponse#getErrorCode()}.
 * <p>
 * Codes are <em>append-only</em> and follow the convention {@code DOMAIN_REASON} so SDKs
 * and the frontend can branch on them deterministically. Never rename a code; introduce
 * a new one and deprecate the old one.
 */
public final class ErrorCodes {

    private ErrorCodes() {}

    // ── Generic ───────────────────────────────────────────────────────────────
    public static final String VALIDATION_FAILED          = "VALIDATION_FAILED";
    public static final String NOT_FOUND                  = "NOT_FOUND";
    public static final String CONFLICT                   = "CONFLICT";
    public static final String UNAUTHORIZED               = "UNAUTHORIZED";
    public static final String FORBIDDEN                  = "FORBIDDEN";
    public static final String METHOD_NOT_ALLOWED         = "METHOD_NOT_ALLOWED";
    public static final String OPTIMISTIC_LOCK_FAILURE    = "OPTIMISTIC_LOCK_FAILURE";
    public static final String INTERNAL_ERROR             = "INTERNAL_ERROR";
    public static final String RATE_LIMITED               = "RATE_LIMITED";

    // ── CSRF ──────────────────────────────────────────────────────────────────
    public static final String CSRF_TOKEN_MISSING         = "CSRF_TOKEN_MISSING";
    public static final String CSRF_TOKEN_MISMATCH        = "CSRF_TOKEN_MISMATCH";
    public static final String CSRF_BAD_ORIGIN            = "CSRF_BAD_ORIGIN";

    // ── Auth / abuse ──────────────────────────────────────────────────────────
    public static final String ACCOUNT_LOCKED             = "ACCOUNT_LOCKED";
    public static final String OTP_LOCKED                 = "OTP_LOCKED";
    public static final String OTP_INVALID                = "OTP_INVALID";
    public static final String SUSPICIOUS_LOGIN_BLOCKED   = "SUSPICIOUS_LOGIN_BLOCKED";
    public static final String INVALID_CREDENTIALS        = "INVALID_CREDENTIALS";

    // ── Idempotency ───────────────────────────────────────────────────────────
    /** Same Idempotency-Key submitted with a different request payload. Never retryable. */
    public static final String IDEMPOTENCY_MISMATCH             = "IDEMPOTENCY_MISMATCH";

    // ── Service / fallback (gateway circuit-breaker) ──────────────────────────
    public static final String AUTH_SERVICE_UNAVAILABLE         = "AUTH_SERVICE_UNAVAILABLE";
    public static final String BOOKING_SERVICE_UNAVAILABLE      = "BOOKING_SERVICE_UNAVAILABLE";
    public static final String AVAILABILITY_SERVICE_UNAVAILABLE = "AVAILABILITY_SERVICE_UNAVAILABLE";
    public static final String PAYMENT_SERVICE_UNAVAILABLE      = "PAYMENT_SERVICE_UNAVAILABLE";
    public static final String NOTIFICATION_SERVICE_UNAVAILABLE = "NOTIFICATION_SERVICE_UNAVAILABLE";
    public static final String SERVICE_UNAVAILABLE              = "SERVICE_UNAVAILABLE";
}
