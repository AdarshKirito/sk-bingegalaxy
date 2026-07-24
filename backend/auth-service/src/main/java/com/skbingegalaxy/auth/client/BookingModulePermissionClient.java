package com.skbingegalaxy.auth.client;

import com.skbingegalaxy.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V71 module-permission lookup against booking-service (the matrix owner).
 *
 * <p>Auth-service owns the customer-directory endpoints that back the binge
 * "Users" menu option, so it must honour the per-binge module matrix the
 * super-admin manages in booking-service. This client reads the denied-modules
 * list off the internal binge snapshot (X-Internal-Secret, same contract
 * payment-/availability-service use) with a 30-second in-process cache.</p>
 *
 * <p><b>Fail-open:</b> if booking-service is unreachable the check passes —
 * user administration must never hard-depend on another service being up;
 * booking-side enforcement still covers its own endpoints.</p>
 */
@Component
@Slf4j
public class BookingModulePermissionClient {

    private static final long CACHE_TTL_MS = 30_000L;

    private final RestClient restClient;
    private final String internalApiSecret;

    /** (bingeId:userId) → [expiryEpochMs, denied module keys]. */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(long expiresAt, Set<String> denied) {}

    public BookingModulePermissionClient(
            RestClient.Builder restClientBuilder,
            @Value("${internal.api.secret}") String internalApiSecret,
            @Value("${services.booking.base-url:http://booking-service:8083}") String bookingBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(bookingBaseUrl).build();
        this.internalApiSecret = internalApiSecret;
    }

    /** Module keys {@code userId} may NOT use in {@code bingeId}; empty on outage (fail-open). */
    @SuppressWarnings("unchecked")
    public Set<String> deniedModules(Long bingeId, Long userId) {
        if (bingeId == null || userId == null) return Set.of();
        String key = bingeId + ":" + userId;
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) return cached.denied();
        try {
            Map<String, Object> body = restClient.get()
                .uri("/api/v1/bookings/internal/binges/{id}?userId={uid}", bingeId, userId)
                .header("X-Internal-Secret", internalApiSecret)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            Set<String> denied = Set.of();
            if (body != null && body.get("data") instanceof Map<?, ?> data
                    && data.get("deniedModules") instanceof List<?> list) {
                denied = Set.copyOf((List<String>) list);
            }
            cache.put(key, new CacheEntry(now + CACHE_TTL_MS, denied));
            return denied;
        } catch (RestClientException ex) {
            log.warn("Module-permission lookup failed for binge={} user={} — failing open: {}",
                bingeId, userId, ex.getMessage());
            return Set.of();
        }
    }

    /**
     * 403 when the super-admin disabled/locked {@code moduleKey} for this admin
     * in the selected binge. SUPER_ADMIN and requests without a binge context
     * (platform-level tooling) pass untouched.
     */
    public void requireModuleAllowed(Long userId, String role, Long bingeId, String moduleKey) {
        if (role == null || !"ADMIN".equalsIgnoreCase(role)) return;
        if (deniedModules(bingeId, userId).contains(moduleKey)) {
            throw new BusinessException("This option is disabled by Super Admin.", HttpStatus.FORBIDDEN);
        }
    }
}
