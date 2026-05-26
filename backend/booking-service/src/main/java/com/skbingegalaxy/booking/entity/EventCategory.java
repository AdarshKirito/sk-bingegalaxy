package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Category for grouping {@link EventType}s under filterable headings
 * (e.g. "Birthdays", "Corporate", "Romantic"). Mirrors the existing
 * "binge_id NULL = global, NOT NULL = per-binge" convention used by
 * EventType/AddOn.
 *
 * <p>Categories are an optional taxonomy — an EventType may have
 * {@code categoryId == null}, in which case it surfaces only under the
 * "All" filter on the customer-facing wizard.
 */
@Entity
@Table(name = "event_categories")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class EventCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning binge. NULL = global (super-admin owned, visible to all binges). */
    private Long bingeId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 500)
    private String description;

    /** Optional category-card cover image. Stored as a serve-URL (same shape as event/add-on images). */
    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
