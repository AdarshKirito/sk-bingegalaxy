package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * V57: maintenance / private-hold window on a venue room.
 *
 * <p>While a block is active, the targeted room behaves as if it
 * is fully booked: any new booking whose slot overlaps the
 * {@code [startAt, endAt)} window is rejected. Blocks never
 * cancel existing bookings — admins must reschedule those
 * separately.
 */
@Entity
@Table(name = "room_blocks", indexes = {
    @Index(name = "idx_room_blocks_room_window", columnList = "room_id, start_at, end_at")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RoomBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
