package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Category for grouping {@link AddOn}s. Replaces the legacy free-text
 * {@code AddOn.category} VARCHAR (kept for one release cycle for backward
 * compatibility, planned drop in V58).
 *
 * <p>Mirrors {@link EventCategory}: NULL {@code bingeId} = global,
 * non-null = per-binge. Backfill populated from existing distinct
 * {@code (binge_id, category)} pairs by V55.
 */
@Entity
@Table(name = "addon_categories")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AddOnCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning binge. NULL = global (super-admin owned, visible to all binges). */
    private Long bingeId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 500)
    private String description;

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
