package com.skbingegalaxy.availability.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "blocked_dates", uniqueConstraints = {
    @UniqueConstraint(name = "uk_blocked_dates_binge_date", columnNames = {"bingeId", "blockedDate"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BlockedDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long bingeId;

    @Column(nullable = false)
    private LocalDate blockedDate;

    @Column(length = 255)
    private String reason;

    @Column(nullable = false)
    private Long blockedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
