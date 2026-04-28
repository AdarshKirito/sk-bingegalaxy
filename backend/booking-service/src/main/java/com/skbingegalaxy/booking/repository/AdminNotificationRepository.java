package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.AdminNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AdminNotificationRepository extends JpaRepository<AdminNotification, Long> {

    /**
     * Lists notifications visible to a specific user: their personal ones plus
     * any role-wide broadcasts ({@code recipient_user_id IS NULL}) targeting
     * their role.
     */
    @Query("SELECT n FROM AdminNotification n "
            + "WHERE n.recipientUserId = :userId "
            + "   OR (n.recipientUserId IS NULL AND n.recipientRole = :role) "
            + "ORDER BY n.createdAt DESC")
    Page<AdminNotification> findVisibleToUser(@Param("userId") Long userId,
                                              @Param("role") String role,
                                              Pageable pageable);

    @Query("SELECT COUNT(n) FROM AdminNotification n "
            + "WHERE (n.recipientUserId = :userId "
            + "    OR (n.recipientUserId IS NULL AND n.recipientRole = :role)) "
            + "  AND n.readAt IS NULL")
    long countUnreadForUser(@Param("userId") Long userId, @Param("role") String role);

    @Modifying
    @Query("UPDATE AdminNotification n SET n.readAt = :now "
            + "WHERE n.readAt IS NULL "
            + "  AND (n.recipientUserId = :userId "
            + "    OR (n.recipientUserId IS NULL AND n.recipientRole = :role))")
    int markAllReadForUser(@Param("userId") Long userId,
                           @Param("role") String role,
                           @Param("now") LocalDateTime now);
}
