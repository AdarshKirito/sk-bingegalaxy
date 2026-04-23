package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.common.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    Optional<User> findByEmailAndRole(String email, UserRole role);

    /**
     * Atomically increment failed_login_attempts at the DB level (single UPDATE)
     * to prevent race conditions when concurrent login attempts read stale counts.
     * Returns the new count so the caller can decide whether to lock the account.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.failedLoginAttempts = u.failedLoginAttempts + 1 WHERE u.id = :id")
    void incrementFailedLoginAttempts(@Param("id") Long id);

    /**
     * Full-text customer search. Uses LIKE with leading wildcard which prevents
     * standard B-tree index usage. For production at scale, create a pg_trgm GIN
     * index: CREATE INDEX idx_user_search ON users USING gin (
     *   (first_name || ' ' || last_name || ' ' || email) gin_trgm_ops);
     */
    @Query("SELECT u FROM User u WHERE u.role = 'CUSTOMER' AND (" +
           "LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "u.phone LIKE CONCAT('%', :q, '%'))")
    Page<User> searchCustomers(@Param("q") String query, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.role = 'CUSTOMER' ORDER BY u.firstName, u.lastName")
    Page<User> findAllCustomers(Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.role = 'ADMIN' ORDER BY u.firstName, u.lastName")
    Page<User> findAllAdmins(Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.role = com.skbingegalaxy.common.enums.UserRole.CUSTOMER " +
           "AND u.active = true AND ((u.birthdayMonth IS NOT NULL AND u.birthdayDay IS NOT NULL) " +
           "OR (u.anniversaryMonth IS NOT NULL AND u.anniversaryDay IS NOT NULL))")
    List<User> findCustomersWithCelebrationReminders();
}
