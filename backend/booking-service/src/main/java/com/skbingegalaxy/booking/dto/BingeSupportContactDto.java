package com.skbingegalaxy.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Customer-facing support contact for a specific binge. Composed by the
 * booking-service with the following precedence:
 * <ol>
 *   <li>Binge-level overrides ({@code supportEmail}, {@code supportPhone},
 *       {@code supportWhatsapp}) configured by the binge admin.</li>
 *   <li>Owning admin's primary email / phone fetched from auth-service via
 *       {@code /api/v1/auth/internal/users/{id}/contact}.</li>
 * </ol>
 *
 * <p>Shape mirrors {@code SupportContactDto} on the auth side so the
 * frontend's existing {@code mergeSupportContact} helper can consume it
 * unchanged.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BingeSupportContactDto {
    private String email;
    /** Human-friendly display, e.g. "+91 98765 43210". */
    private String phoneDisplay;
    /** Tel-link friendly, e.g. "+919876543210". */
    private String phoneRaw;
    /** wa.me-friendly digits-only with country code, e.g. "919876543210". */
    private String whatsappRaw;
    /** Optional support hours; populated from CMS when available. */
    private String hours;
}
