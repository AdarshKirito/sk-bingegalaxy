package com.skbingegalaxy.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal, customer-safe projection of an admin's contact details exposed to
 * trusted internal services (booking-service) over the {@code /api/v1/auth/internal}
 * surface.
 *
 * <p>This DTO is deliberately narrow: only fields that are safe to surface in
 * a customer-facing "Need help?" widget (name + reachable channels) are
 * returned. Personal / out-of-band phone numbers and other PII (address,
 * password hash, role, audit metadata) are NEVER serialized here.</p>
 *
 * <p>The "whatsapp" channel mirrors the public phone since binge admins use
 * the same number for both; downstream callers may override with a per-binge
 * WhatsApp override stored on the {@code Binge} entity.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminContactDto {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String phoneCountryCode;
    private String whatsapp;
    private String whatsappCountryCode;
}
