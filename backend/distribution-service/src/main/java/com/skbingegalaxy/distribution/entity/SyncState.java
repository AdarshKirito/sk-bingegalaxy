package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * How current our data is on one destination — expressed as <b>named states</b> rather
 * than a single "synced N minutes ago".
 *
 * <p>Every researched provider behaves differently, and one timestamp cannot describe
 * them honestly: Viator and OCTO <em>pull</em> availability on demand (so there is no
 * push to be late with), GetYourGuide fetches on a schedule of roughly every 8 days for
 * 365 days ahead, and Google Things to Do is a full-replacement feed whose products are
 * removed if it is not refreshed within 30 days.
 *
 * <p>Which field matters is therefore a property of the destination. A pull-based
 * destination showing an empty {@link #lastFeedPublishedAt} is healthy; a feed-based one
 * showing the same is about to be delisted.
 */
@Entity
@Table(name = "sync_state")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SyncState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_destination_id", nullable = false, unique = true)
    private Long connectionDestinationId;

    /** Last time the provider pulled availability from us. Relevant to OCTO/Viator. */
    @Column(name = "last_realtime_at")
    private LocalDateTime lastRealtimeAt;

    @Column(name = "last_delta_ack_at")
    private LocalDateTime lastDeltaAckAt;

    /** Relevant to feed-based destinations only. */
    @Column(name = "last_feed_published_at")
    private LocalDateTime lastFeedPublishedAt;

    @Column(name = "last_reconciled_at")
    private LocalDateTime lastReconciledAt;

    @Column(name = "last_full_resync_at")
    private LocalDateTime lastFullResyncAt;

    @Column(name = "accepted_inventory_version")
    private Long acceptedInventoryVersion;

    /**
     * When the provider will consider our data stale.
     *
     * <p>Set <b>earlier</b> than the provider's own deadline. Google removes products at
     * 30 days, so this is populated at 21 — an alarm that fires after the delisting is
     * not an alarm.
     */
    @Column(name = "stale_after")
    private LocalDateTime staleAfter;

    @Column(name = "consecutive_failures", nullable = false)
    @Builder.Default
    private int consecutiveFailures = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
