package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.AuthAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthAuditLogRepository extends JpaRepository<AuthAuditLog, Long> {

    @Query("SELECT a FROM AuthAuditLog a " +
        "WHERE (:eventType IS NULL OR a.eventType = :eventType) " +
        "  AND (:actorId   IS NULL OR a.actorId   = :actorId) " +
        "  AND (:targetId  IS NULL OR a.targetId  = :targetId) " +
        "ORDER BY a.createdAt DESC, a.id DESC")
    Page<AuthAuditLog> search(@Param("eventType") String eventType,
                              @Param("actorId") Long actorId,
                              @Param("targetId") Long targetId,
                              Pageable pageable);

    Page<AuthAuditLog> findByTargetIdOrActorIdOrderByCreatedAtDesc(Long targetId, Long actorId, Pageable pageable);
}
