package com.skbingegalaxy.distribution.dto;

import com.skbingegalaxy.distribution.entity.Connection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Create a venue's connection to one provider.
 *
 * <p><b>No secret is accepted here.</b> An earlier shape of this request carried an
 * {@code apiKey} field, which would have put a provider secret in a request body, an
 * access log, a browser's memory and — one careless line later — a database column. The
 * secret is provisioned out of band and named by {@link #credentialRef}; see
 * {@code CredentialStore}.
 */
@Data
public class CreateConnectionRequest {

    @NotBlank(message = "Provider is required")
    @Size(max = 40)
    private String providerCode;

    /**
     * SANDBOX unless explicitly set. Defaulting to production would make the riskier
     * choice the silent one.
     */
    private Connection.Environment environment = Connection.Environment.SANDBOX;

    /**
     * Pointer to the provisioned secret, e.g. {@code viator/binge-12/production} — not
     * the secret. Optional: a PLATFORM_MANAGED provider (the OCTO simulator) needs none,
     * and the service rejects a missing reference only for providers that require one.
     */
    @Size(max = 200)
    private String credentialRef;
}
