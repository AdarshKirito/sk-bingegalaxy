package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {

    boolean existsByJti(String jti);

    @Modifying
    @Query("delete from RevokedToken r where r.expiresAt < :cutoff")
    int deleteAllByExpiresAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
