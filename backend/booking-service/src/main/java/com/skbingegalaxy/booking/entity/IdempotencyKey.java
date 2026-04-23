package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Stripe-style Idempotency-Key record for booking POST endpoints.
 *
 * <p>Composite PK (key, method, path, userId) prevents collisions when a
 * client reuses a key across different endpoints or users. The stored
 * SHA-256 hash lets us detect "same key, different payload" — the classic
 * client bug that weak dedupe would silently accept.
 */
@Entity
@Table(name = "idempotency_key")
@IdClass(IdempotencyKey.Pk.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Id
    @Column(name = "http_method", length = 8, nullable = false)
    private String httpMethod;

    @Id
    @Column(name = "request_path", length = 255, nullable = false)
    private String requestPath;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class Pk implements Serializable {
        private String idempotencyKey;
        private String httpMethod;
        private String requestPath;
        private Long userId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(idempotencyKey, pk.idempotencyKey)
                && Objects.equals(httpMethod, pk.httpMethod)
                && Objects.equals(requestPath, pk.requestPath)
                && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(idempotencyKey, httpMethod, requestPath, userId);
        }
    }
}
