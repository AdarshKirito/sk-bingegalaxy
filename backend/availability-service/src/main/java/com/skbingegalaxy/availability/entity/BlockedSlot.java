package com.skbingegalaxy.availability.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "blocked_slots", uniqueConstraints = {
    @UniqueConstraint(name = "uk_blocked_slot_binge", columnNames = {"bingeId", "slotDate", "startHour"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BlockedSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long bingeId;

    @Column(nullable = false)
    private LocalDate slotDate;

    /**
     * Start of blocked period in <b>minutes since midnight</b> (0–1439).
     * DB column kept as {@code startHour} for backward compatibility; the
     * public API (DTOs) uses {@code startMinute} to avoid ambiguity.
     */
    @Column(nullable = false)
    private int startHour;

    /**
     * End of blocked period in <b>minutes since midnight</b> (1–1440).
     * DB column kept as {@code endHour} for backward compatibility.
     */
    @Column(nullable = false)
    private int endHour;

    @Column(length = 255)
    private String reason;

    @Column(nullable = false)
    private Long blockedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
