package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

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

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
