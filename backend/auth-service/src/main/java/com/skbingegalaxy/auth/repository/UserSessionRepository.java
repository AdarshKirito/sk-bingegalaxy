package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.UserSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByRefreshJti(String refreshJti);

    @Query("SELECT s FROM UserSession s WHERE s.userId = :userId AND s.revokedAt IS NULL " +
        "AND s.expiresAt > CURRENT_TIMESTAMP ORDER BY s.lastSeenAt DESC")
    List<UserSession> findActiveByUserId(@Param("userId") Long userId);

    @Query("SELECT s FROM UserSession s WHERE s.revokedAt IS NULL AND s.expiresAt > CURRENT_TIMESTAMP " +
        "ORDER BY s.lastSeenAt DESC")
    Page<UserSession> findAllActive(Pageable pageable);

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = CURRENT_TIMESTAMP, s.revokedBy = :by, s.revokeReason = :reason " +
        "WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") Long userId, @Param("by") Long by, @Param("reason") String reason);

    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
