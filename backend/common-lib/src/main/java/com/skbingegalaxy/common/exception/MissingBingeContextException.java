package com.skbingegalaxy.common.exception;

/**
 * Thrown when a binge-scoped operation runs without a binge selected — i.e. the
 * {@code X-Binge-Id} request header was missing (or BingeContextFilter wasn't applied).
 *
 * <p>This is a <em>client</em> precondition, not a server fault, so it extends
 * {@link BusinessException} (HTTP 400) rather than bubbling up as a generic 500 via the
 * fallback handler. That keeps "no binge selected" out of server-error dashboards and
 * error-rate alerts while still failing the request with a clear message. Best-effort
 * callers (e.g. dashboard count badges) typically catch and ignore it.
 *
 * <p>In non-web code paths (Kafka listeners, scheduled jobs) the HTTP status is irrelevant;
 * the exception still propagates and fails processing exactly as before.
 */
public class MissingBingeContextException extends BusinessException {
    public MissingBingeContextException(String message) {
        super(message); // BusinessException defaults to HttpStatus.BAD_REQUEST
    }
}
