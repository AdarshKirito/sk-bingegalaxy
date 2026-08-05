package com.skbingegalaxy.distribution.dto;

import com.skbingegalaxy.distribution.entity.Provider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A connectable provider, with everything the console needs to render the RIGHT form.
 *
 * <p>The design's rule is that authentication is <b>connector-specific, never the
 * universal API-key screen</b>. Google Things to Do is an SFTP feed behind a content
 * licence; Actions Center is basic auth rotated every six months; the simulator needs no
 * credential at all. A single "paste your API key" box would be wrong for three of the
 * five providers seeded here, so the shape of the form comes from {@link #authMethod}
 * and {@link #requiresCredential}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderDto {
    private String code;
    private String displayName;
    private Provider.ProviderKind providerKind;
    private Provider.AuthMethod authMethod;
    private Provider.CertificationState certificationState;
    private String docsUrl;

    /** False for PLATFORM_MANAGED providers — the console must not ask for a secret. */
    private boolean requiresCredential;

    /**
     * True only when this deployment's CredentialStore can accept a submitted secret.
     * When false the console must tell the operator to provision it out of band rather
     * than offering an input that cannot work.
     */
    private boolean credentialSubmissionSupported;

    private List<CapabilityDto> capabilities;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapabilityDto {
        private String key;
        private boolean enabled;
        private Integer intValue;
        /** Evidence for the value — an official doc reference, or UNVERIFIED. */
        private String notes;
    }
}
