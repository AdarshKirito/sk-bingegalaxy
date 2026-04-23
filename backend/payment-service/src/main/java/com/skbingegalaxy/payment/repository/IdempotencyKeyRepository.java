package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKey.Pk> {

    Optional<IdempotencyKey> findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(
        String idempotencyKey, String httpMethod, String requestPath, Long userId);

    @Modifying
    @Query("delete from IdempotencyKey k where k.expiresAt < :cutoff")
    int deleteAllByExpiresAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
