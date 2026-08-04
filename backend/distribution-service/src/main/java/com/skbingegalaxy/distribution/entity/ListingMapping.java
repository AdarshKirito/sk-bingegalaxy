package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * An SK Binge {@code EventType} published to one destination through one connection.
 *
 * <p>Readiness is per <em>(listing × destination)</em>, not per listing, because every
 * destination demands different content — meeting point, age restrictions, inclusions,
 * voucher instructions — and a listing that satisfies Viator may be incomplete for
 * GetYourGuide.
 */
@Entity
@Table(name = "listing_mappings")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ListingMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_destination_id", nullable = false)
    private Long connectionDestinationId;

    /**
     * References {@code booking_db.event_types}. Deliberately <b>not</b> a database
     * foreign key: it crosses a service boundary, and enforcing it here would couple two
     * schemas that must stay independently deployable.
     */
    @Column(name = "event_type_id", nullable = false)
    private Long eventTypeId;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    @Column(name = "external_product_id", length = 200)
    private String externalProductId;

    /** NOT NULL with a DB default of '{}' — see the note on {@code Provider}. */
    @Column(name = "external_option_ids", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private String[] externalOptionIds = new String[0];

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_state", nullable = false, length = 20)
    @Builder.Default
    private PublishState publishState = PublishState.DRAFT;

    /**
     * Percentage of that destination's <b>mandatory</b> fields satisfied. A database
     * CHECK enforces that {@code LIVE} requires 100, so no service bug can publish a
     * half-ready listing — the rule lives in the schema rather than in prose.
     */
    @Column(name = "readiness_pct", nullable = false)
    @Builder.Default
    private int readinessPct = 0;

    /**
     * Human-readable, actionable reasons. The console shows these inline with a fix link.
     * NOT NULL with a DB default of '{}' — see the note on {@code Provider}.
     */
    @Column(name = "blocking_reasons", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private String[] blockingReasons = new String[0];

    @Column(name = "last_published_at")
    private LocalDateTime lastPublishedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum PublishState {
        DRAFT, READY, PUBLISHING, LIVE, PAUSED,
        /** Blocked by an SK Binge-side prerequisite, e.g. no turnover buffer configured. */
        BLOCKED,
        FAILED
    }
}
