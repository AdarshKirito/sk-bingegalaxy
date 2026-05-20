package com.skbingegalaxy.booking.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Service-to-service client that consults auth-service to determine whether a
 * given {@code (resourceType, resourceId)} is currently locked by a native
 * super-admin.
 *
 * <p>This is the server-side enforcement leg of the Authority Handover lock
 * system. Frontend disable-on-lock UX is advisory only; without this guard a
 * delegated admin could bypass it via a direct API call. The
 * {@link com.skbingegalaxy.booking.controller.AdminCurrencyController} (and any
 * other lock-aware controller) calls this on every mutation that the gateway
 * has flagged as a delegated request (X-Authority-Delegated=true). Native
 * super-admins skip the check — they can always release their own locks via
 * the Authority Handover console.
 *
 * <p>The endpoint is hosted at {@code /api/v1/auth/authority/internal/locks/lookup}
 * and protected by {@link com.skbingegalaxy.common.security.InternalApiAuthFilter}
 * via the {@code X-Internal-Secret} header — preventing browsers from calling
 * it directly.</p>
 *
 * <h3>Failure mode</h3>
 * If auth-service is unreachable or returns an error, this client returns
 * {@code null} — i.e. <em>fail-open for native super-admins, fail-closed for
 * delegated admins is enforced by callers</em>. Callers MUST decide what to do
 * with a null result based on their own security posture; for currency
 * mutation we treat unknown lock status as "not locked" to avoid blocking
 * legitimate operators during a transient outage. The frontend lock badge is
 * the secondary cue, and locks are inherently rare events.
 */
@Service
@Slf4j
public class HttpAuthorityLockClient {

    private final RestClient restClient;
    private final String authServiceUrl;
    private final String internalApiSecret;

    public HttpAuthorityLockClient(
            RestClient.Builder restClientBuilder,
            @Value("${auth.service.url:${AUTH_SERVICE_URL:http://auth-service:8081}}") String authServiceUrl,
            @Value("${internal.api.secret}") String internalApiSecret
    ) {
        this.restClient = restClientBuilder.build();
        this.authServiceUrl = authServiceUrl;
        this.internalApiSecret = internalApiSecret;
    }

    /**
     * @return the active lock if one exists, else {@code null}. {@code null} is
     *         also returned for any transient failure — callers MUST treat this
     *         as "lock unknown" rather than "lock absent" if they need
     *         fail-closed semantics.
     */
    public ResourceLockSummary lookup(String resourceType, String resourceId) {
        if (resourceType == null || resourceType.isBlank()
                || resourceId == null || resourceId.isBlank()) {
            return null;
        }
        try {
            ApiResponse<ResourceLockSummary> resp = restClient.get()
                .uri(authServiceUrl + "/api/v1/auth/authority/internal/locks/lookup?type={t}&id={i}",
                     resourceType, resourceId)
                .header("X-Internal-Secret", internalApiSecret)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
            return resp != null ? resp.getData() : null;
        } catch (RestClientException ex) {
            log.warn("auth.lock.lookup.fail type={} id={} err={}",
                resourceType, resourceId, ex.getMessage());
            return null;
        }
    }

    /**
     * Minimal projection of the auth-service ResourceLockDto — we only need the
     * fields the controller surfaces in its 423 Locked error message.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceLockSummary {
        private Long id;
        private String resourceType;
        private String resourceId;
        private Long lockedBy;
        private String lockedByName;
        private String reason;
    }
}
