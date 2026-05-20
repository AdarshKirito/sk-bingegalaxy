package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.AuthorityGrant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuthorityGrantRepository extends JpaRepository<AuthorityGrant, Long> {

    /**
     * Returns every grant ever issued to {@code granteeUserId}, newest first.
     * Used by the super-admin "Authority Handover" page to show grant history.
     */
    List<AuthorityGrant> findByGranteeUserIdOrderByGrantedAtDesc(Long granteeUserId);

    /**
     * Active = not revoked and not yet expired. EAGER fetch on scopes by default
     * (see entity), so this is safe to consume after the transaction closes.
     */
    @Query("""
        SELECT g FROM AuthorityGrant g
        WHERE g.granteeUserId = :userId
          AND g.revokedAt IS NULL
          AND g.expiresAt > :now
        ORDER BY g.grantedAt DESC
    """)
    List<AuthorityGrant> findActiveForUser(@Param("userId") Long userId,
                                           @Param("now") LocalDateTime now);

    /**
     * Most recently issued grant overall. Used by the super-admin dashboard for
     * the "currently delegated" panel.
     */
    @Query("""
        SELECT g FROM AuthorityGrant g
        WHERE g.revokedAt IS NULL
          AND g.expiresAt > :now
        ORDER BY g.grantedAt DESC
    """)
    Page<AuthorityGrant> findAllActive(@Param("now") LocalDateTime now, Pageable pageable);

    /** Audit history page (active + expired + revoked) for the super-admin viewer. */
    Page<AuthorityGrant> findAllByOrderByGrantedAtDesc(Pageable pageable);

    /**
     * Atomic revoke. Returns 1 if the row transitioned to revoked, 0 if it was already
     * revoked/expired (caller should treat 0 as a no-op idempotent success).
     */
    @Modifying
    @Query("""
        UPDATE AuthorityGrant g
           SET g.revokedAt = :now, g.revokedBy = :revokedBy, g.revokeReason = :reason
         WHERE g.id = :id
           AND g.revokedAt IS NULL
    """)
    int revoke(@Param("id") Long id,
               @Param("revokedBy") Long revokedBy,
               @Param("reason") String reason,
               @Param("now") LocalDateTime now);

    Optional<AuthorityGrant> findById(Long id);
}
