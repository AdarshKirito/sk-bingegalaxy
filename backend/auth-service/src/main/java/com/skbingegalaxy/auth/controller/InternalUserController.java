package com.skbingegalaxy.auth.controller;

import com.skbingegalaxy.auth.dto.AdminContactDto;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service (trusted) user lookups. Lives under {@code /api/v1/auth/internal/**},
 * which the auth {@code SecurityConfig} restricts to the SYSTEM role — reachable only by a
 * caller presenting the shared {@code X-Internal-Secret} (validated by
 * {@code InternalApiAuthFilter}). Never exposed to browsers/customers.
 *
 * <p>Backs {@code HttpAuthContactClient.fetchAdminContact(...)} in booking-service, which
 * resolves a recipient's reachable channels (email/phone) so an in-app message can be
 * fanned out to the notification-service (email/SMS/push). Returns a narrow, customer-safe
 * projection — no password hash, role, or audit metadata.
 */
@RestController
@RequestMapping("/api/v1/auth/internal")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserRepository userRepository;

    @GetMapping("/users/{id}/contact")
    public ResponseEntity<ApiResponse<AdminContactDto>> getUserContact(@PathVariable Long id) {
        AdminContactDto dto = userRepository.findById(id)
            .map(u -> AdminContactDto.builder()
                .id(u.getId())
                .firstName(u.getFirstName())
                .lastName(u.getLastName())
                .email(u.getEmail())
                .phone(u.getPhone())
                .phoneCountryCode(u.getPhoneCountryCode())
                // Users reuse their public phone for WhatsApp; a per-binge override
                // (on the Binge entity) can supersede this downstream.
                .whatsapp(u.getPhone())
                .whatsappCountryCode(u.getPhoneCountryCode())
                .build())
            .orElse(null);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }
}
