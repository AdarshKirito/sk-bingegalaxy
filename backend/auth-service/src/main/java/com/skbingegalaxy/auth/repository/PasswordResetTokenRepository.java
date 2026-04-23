package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenAndUsedFalse(String token);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE PasswordResetToken t SET t.used = true WHERE t.user.id = :userId AND t.used = false")
    void markAllUnusedAsUsedForUser(@org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * Physically purge tokens that have either been used or expired and whose
     * {@code expiresAt} is older than the cut-off. Keeps table size bounded and
     * ensures password-reset auditing doesn't leak forever.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :cutoff")
    int deleteAllByExpiresAtBefore(@org.springframework.data.repository.query.Param("cutoff") LocalDateTime cutoff);
}

