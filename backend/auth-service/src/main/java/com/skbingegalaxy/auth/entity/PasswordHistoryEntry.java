package com.skbingegalaxy.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Stores BCrypt hashes of a user's past passwords so a change-password / reset-password
 * flow can refuse to reuse the last N passwords. Entries older than the retention window
 * are pruned by {@code PasswordHistoryService}.
 */
@Entity
@Table(name = "password_history")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PasswordHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
