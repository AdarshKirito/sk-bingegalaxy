package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A marketplace where a traveller actually finds and buys the listing.
 *
 * <p>Separate from {@link Provider} because the same destination can be reached through
 * different pipes: Viator reached via a Bókun connection and Viator reached via a direct
 * Supplier API connection are the <em>same</em> destination with the same commission and
 * the same traveller-facing policy, but entirely different technical behaviour.
 */
@Entity
@Table(name = "destinations")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Destination {

    @Id
    @Column(length = 40)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "operated_by_provider_code", nullable = false, length = 40)
    private String operatedByProviderCode;

    /** NOT NULL with a DB default of '{}' — see the note on {@code Provider}. */
    @Column(name = "supported_countries", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private String[] supportedCountries = new String[0];

    /**
     * FALSE for feed-only destinations.
     *
     * <p>Google Things to Do is the case this flag exists for: it is an SFTP JSON feed
     * plus a deep link, the traveller completes checkout on SK Binge, and <b>no
     * reservation is ever delivered back</b>. An earlier design showed a Google booking
     * sitting in the reservation inbox — that object does not exist. A Google conversion
     * is a canonical SK Binge booking carrying an attribution source.
     *
     * <p>The reservations screen must render no rows for a destination where this is
     * false.
     */
    @Column(name = "delivers_reservations", nullable = false)
    @Builder.Default
    private boolean deliversReservations = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
