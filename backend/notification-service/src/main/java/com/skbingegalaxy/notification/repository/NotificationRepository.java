package com.skbingegalaxy.notification.repository;

import com.skbingegalaxy.notification.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {

    List<Notification> findByRecipientEmailOrderByCreatedAtDesc(String email);

    List<Notification> findByBookingRefOrderByCreatedAtDesc(String bookingRef);

    List<Notification> findByBookingRefAndRecipientEmailOrderByCreatedAtDesc(String bookingRef, String recipientEmail);

    Page<Notification> findByType(String type, Pageable pageable);

    List<Notification> findBySentFalseAndRetryCountLessThan(int maxRetries);

    Page<Notification> findBySentFalseAndRetryCountLessThan(int maxRetries, Pageable pageable);

    /**
     * Find retryable notifications: unsent, under max retries, and whose
     * nextRetryAt is either null (legacy) or in the past.
     */
    @Query("{ 'sent': false, 'retryCount': { $lt: ?0 }, " +
           "'$or': [ { 'nextRetryAt': null }, { 'nextRetryAt': { $lte: ?1 } } ] }")
    List<Notification> findRetryableNotifications(int maxRetries, LocalDateTime now, Pageable pageable);

    long countBySentTrue();

    long countBySentFalse();

    boolean existsByBookingRefAndType(String bookingRef, String type);

    boolean existsByRecipientEmailAndType(String recipientEmail, String type);

    boolean existsByBookingRefAndTypeAndSentTrueAndCreatedAtAfter(
        String bookingRef, String type, LocalDateTime cutoff);

    boolean existsByRecipientEmailAndTypeAndSentTrueAndCreatedAtAfter(
        String recipientEmail, String type, LocalDateTime cutoff);

    /** Find notifications in a digest group that haven't been digested yet. */
    List<Notification> findByDigestGroupAndDigestedFalseAndSentTrue(String digestGroup);

    /** Find ALL un-digested sent notifications that have a non-null digestGroup. */
    @Query("{ 'digestGroup': { $ne: null }, 'digested': false, 'sent': true }")
    List<Notification> findUndigestedWithDigestGroup();
}
