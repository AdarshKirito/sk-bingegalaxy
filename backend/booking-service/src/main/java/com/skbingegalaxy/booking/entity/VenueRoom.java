package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "venue_rooms", indexes = {
    @Index(name = "idx_venue_room_binge", columnList = "bingeId")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class VenueRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bingeId;

    @Column(nullable = false, length = 100)
    private String name;

    /** MAIN_HALL, PRIVATE_ROOM, VIP_LOUNGE, OUTDOOR, MEETING_ROOM */
    @Column(nullable = false, length = 30)
    private String roomType;

    /** Maximum concurrent bookings this room can handle at one time. */
    @Column(nullable = false)
    @Builder.Default
    private int capacity = 1;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // ── V56: production lifecycle ──────────────────────────────
    /** Price added to the booking base price when this room is selected. */
    @Column(name = "price_addition", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal priceAddition = BigDecimal.ZERO;

    /** Approval workflow status. Defaults to APPROVED for grandfathered rows. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private RoomApprovalStatus status = RoomApprovalStatus.APPROVED;

    @Column(name = "approval_decided_by")
    private Long approvalDecidedBy;

    @Column(name = "approval_decided_at")
    private LocalDateTime approvalDecidedAt;

    @Column(name = "approval_rejection_reason", length = 500)
    private String approvalRejectionReason;

    /** Photo gallery (managed via venue_room_images). */
    @ElementCollection
    @CollectionTable(
        name = "venue_room_images",
        joinColumns = @JoinColumn(name = "room_id"),
        indexes = @Index(name = "idx_venue_room_images_room", columnList = "room_id")
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "image_url", length = 1000)
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

