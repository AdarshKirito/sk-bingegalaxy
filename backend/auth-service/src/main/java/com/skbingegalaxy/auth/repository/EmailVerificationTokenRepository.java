package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByTokenHashAndUsedFalse(String tokenHash);

    @Modifying
    @Query("UPDATE EmailVerificationToken e SET e.used = TRUE WHERE e.userId = :userId AND e.used = FALSE")
    int invalidateAllForUser(@Param("userId") Long userId);
}
