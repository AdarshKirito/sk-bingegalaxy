package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A technical system SK Binge can exchange distribution data with.
 *
 * <p>Deliberately distinct from {@link Destination}. The previous design used one word
 * — "channel" — for both, which made an ordinary arrangement unrepresentable: a
 * reservation can arrive <em>through</em> Bókun while being sold <em>on</em> Viator, and
 * commission, traveller support and attribution all follow the destination rather than
 * the pipe it travelled down.
 *
 * <p>Platform-owned catalogue. Venues do not create providers; they create
 * {@link Connection}s to them.
 */
@Entity
@Table(name = "providers")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Provider {

    @Id
    @Column(length = 40)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    /**
     * CONNECTIVITY = a pipe to other destinations (Bókun).
     * DESTINATION  = a marketplace only (Google Things to Do).
     * BOTH         = Viator, which is its own connectivity API and its own marketplace.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_kind", nullable = false, length = 20)
    private ProviderKind providerKind;

    /**
     * How a venue authorises us. Never assume an API key: Google Things to Do is an
     * SFTP feed behind a content licence agreement, and Actions Center is basic auth
     * rotated every six months.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", nullable = false, length = 24)
    private AuthMethod authMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "certification_state", nullable = false, length = 24)
    @Builder.Default
    private CertificationState certificationState = CertificationState.NONE;

    /**
     * ISO-3166-1 alpha-2 codes. Empty = no restriction recorded. A connection may only
     * reach a destination that operates in the venue's country — the visible
     * consequence of the platform being global (decision B1).
     */
    /*
     * NOT NULL with a DB default of '{}', and the entity MUST supply the empty array
     * itself. Hibernate includes a mapped column in the INSERT even when the field is
     * null, so the database default never applies and a null field becomes
     * "null value in column violates not-null constraint" on the first save.
     * EntitySchemaParityIT cannot catch this: `validate` checks types, not nullability.
     */
    @Column(name = "supported_countries", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private String[] supportedCountries = new String[0];

    @Column(name = "docs_url", length = 500)
    private String docsUrl;

    /**
     * Seeded FALSE for every real provider. Nothing is connectable until a super-admin
     * activates it, which should follow the still-open commercial question of whether
     * any experiences reseller will list private venue hire at all.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum ProviderKind { CONNECTIVITY, DESTINATION, BOTH }

    public enum AuthMethod {
        /** Provider-hosted OAuth authorisation. */
        OAUTH,
        /** A key the venue pastes. Connector-specific — never the universal screen. */
        API_KEY,
        /** Google Things to Do: SFTP JSON feed, full replacement each upload. */
        SFTP_FEED,
        /** SK Binge holds one platform-level authorisation (e.g. the simulator). */
        PLATFORM_MANAGED,
        /** Access is purely contractual; no technical credential exists yet. */
        CONTRACT_ONLY
    }

    public enum CertificationState { NONE, SELF, PROVIDER_REVIEWED, PILOT_REQUIRED }
}
