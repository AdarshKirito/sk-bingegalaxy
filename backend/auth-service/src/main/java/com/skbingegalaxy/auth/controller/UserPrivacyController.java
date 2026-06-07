package com.skbingegalaxy.auth.controller;

import com.skbingegalaxy.auth.service.UserAnonymizationService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * DPDP (India's Digital Personal Data Protection Act 2023) and GDPR-style
 * privacy endpoints.
 *
 * URL-level security is enforced in SecurityConfig:
 * <ul>
 *   <li>{@code DELETE /api/v1/auth/privacy/me} → authenticated (any logged-in customer)</li>
 *   <li>{@code POST /api/v1/auth/privacy/admin/**} → SUPER_ADMIN only</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth/privacy")
@RequiredArgsConstructor
public class UserPrivacyController {

    private final UserAnonymizationService anonymizationService;

    /**
     * Customer right-to-erasure request.
     * Soft-deletes the account and schedules PII anonymization after the data
     * retention period (default 30 days). All active sessions are revoked immediately.
     */
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> requestMyDeletion(
            @RequestHeader("X-User-Id") Long userId) {
        anonymizationService.requestDeletion(userId);
        return ResponseEntity.ok(ApiResponse.ok(
            "Erasure request submitted. Your account will be deactivated immediately "
            + "and all personal data will be permanently removed within 30 days.", (Void) null));
    }

    /**
     * SUPER_ADMIN: immediately anonymize a specific user's PII.
     * Used to fulfil urgent regulatory orders or law-enforcement requests.
     */
    @PostMapping("/admin/anonymize/{userId}")
    public ResponseEntity<ApiResponse<Void>> anonymizeUser(@PathVariable Long userId) {
        anonymizationService.anonymizeUser(userId);
        return ResponseEntity.ok(ApiResponse.ok(
            "User " + userId + " has been anonymized. All PII fields have been replaced.", (Void) null));
    }
}
