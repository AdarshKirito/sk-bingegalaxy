package com.skbingegalaxy.distribution.credential;

import java.util.Optional;

/**
 * Where provider secrets live — which is <b>never</b> {@code distribution_db}.
 *
 * <p>The {@code connections} table holds a {@code credential_ref} (a pointer) and a
 * {@code credential_hint} (a masked tail, safe to show a browser). The secret itself is
 * resolved through this port at call time and is never persisted by this service, never
 * returned by an API, and never logged.
 *
 * <p><b>Why a port rather than a column.</b> A secret in a service database is a secret
 * in every database backup, every replica, every {@code pg_dump} a developer takes, and
 * every screen that renders a row. This repository has already had one credential
 * incident; the cheapest way not to have another is to make storing a secret impossible
 * rather than discouraged.
 */
public interface CredentialStore {

    /**
     * Resolve a secret by its reference.
     *
     * @return empty when the reference is unknown — callers must treat that as
     *         "connection not usable", never as "no authentication required".
     */
    Optional<String> resolve(String credentialRef);

    /**
     * Whether this store can accept a secret submitted through the API.
     *
     * <p>Deliberately explicit. An implementation that cannot write must say so, so the
     * service can refuse the request with a clear instruction instead of appearing to
     * succeed and leaving an unusable connection behind.
     */
    boolean supportsWrite();

    /**
     * Persist a secret and return its reference.
     *
     * @throws UnsupportedOperationException when {@link #supportsWrite()} is false.
     */
    String store(String credentialRef, String secret);
}
