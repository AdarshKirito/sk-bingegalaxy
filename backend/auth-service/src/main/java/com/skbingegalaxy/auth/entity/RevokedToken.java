package com.skbingegalaxy.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persisted marker indicating that a specific JWT (identified by its {@code jti} claim)
 * has been revoked before its natural expiry. Rows older than their {@code expiresAt}
 * are pruned periodically to keep this table bounded.
 */
@Entity
@Table(name = "revoked_token")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RevokedToken {

    @Id
    @Column(name = "jti", length = 64, nullable = false)
    private String jti;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "token_type", length = 16, nullable = false)
    private String tokenType;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "revoked_at", nullable = false, updatable = false)
    private LocalDateTime revokedAt;
}
