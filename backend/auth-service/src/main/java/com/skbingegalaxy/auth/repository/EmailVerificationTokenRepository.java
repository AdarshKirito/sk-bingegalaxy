package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByTokenHashAndUsedFalse(String tokenHash);

    /**
     * Fetch the latest unused token for a user without loading the entire table.
     * Replaces the old findAll().stream().filter(...) N+1 pattern.
     */
    @Query("SELECT e FROM EmailVerificationToken e WHERE e.userId = :userId AND e.used = FALSE ORDER BY e.createdAt DESC LIMIT 1")
    Optional<EmailVerificationToken> findLatestUnusedForUser(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE EmailVerificationToken e SET e.used = TRUE WHERE e.userId = :userId AND e.used = FALSE")
    int invalidateAllForUser(@Param("userId") Long userId);
}
