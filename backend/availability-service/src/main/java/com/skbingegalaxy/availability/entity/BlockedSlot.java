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
     * Start of blocked period. For admin-created blocked slots this is typically
     * an hour (0-23); for booking-created blocks it may represent minutes since
     * midnight. Named 'startHour' for DB-column compatibility.
     */
    @Column(nullable = false)
    private int startHour;

    /** End of blocked period — same unit as startHour. */
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
