package com.skbingegalaxy.booking.client;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Service-to-service client that resolves an admin user's customer-safe
 * contact details from auth-service. Used by the per-binge support contact
 * endpoint to fall back from binge-level overrides to the binge owner's own
 * phone / email when the per-binge override is blank.
 */
@Service
@Slf4j
public class HttpAuthContactClient {

    private final RestClient restClient;
    private final String authServiceUrl;
    private final String internalApiSecret;

    public HttpAuthContactClient(
            RestClient.Builder restClientBuilder,
            @Value("${auth.service.url:${AUTH_SERVICE_URL:http://auth-service:8081}}") String authServiceUrl,
            @Value("${internal.api.secret}") String internalApiSecret
    ) {
        this.restClient = restClientBuilder.build();
        this.authServiceUrl = authServiceUrl;
        this.internalApiSecret = internalApiSecret;
    }

    /**
     * @return the admin's contact projection, or {@code null} if the admin
     *         user could not be resolved (deleted, unauthorized, network
     *         failure). Callers MUST tolerate null and surface only the
     *         binge-level overrides (or no contact at all) in that case.
     */
    public AdminContact fetchAdminContact(Long adminUserId) {
        if (adminUserId == null) {
            return null;
        }
        try {
            ApiResponse<AdminContact> resp = restClient.get()
                .uri(authServiceUrl + "/api/v1/auth/internal/users/{id}/contact", adminUserId)
                .header("X-Internal-Secret", internalApiSecret)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
            return resp != null ? resp.getData() : null;
        } catch (RestClientException ex) {
            // Don't fail the public support-contact lookup if auth-service is
            // unavailable; the customer simply sees the binge-level overrides
            // (or the platform default) — matching how existing fallbacks
            // work in HttpAvailabilityClient.
            log.warn("Failed to fetch admin contact for adminId={}: {}", adminUserId, ex.getMessage());
            return null;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdminContact {
        private Long id;
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private String phoneCountryCode;
        private String whatsapp;
        private String whatsappCountryCode;
    }
}
