package com.skbingegalaxy.notification.repository;

import com.skbingegalaxy.notification.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {

    List<Notification> findByRecipientEmailOrderByCreatedAtDesc(String email);

    List<Notification> findByBookingRefOrderByCreatedAtDesc(String bookingRef);

    List<Notification> findByBookingRefAndRecipientEmailOrderByCreatedAtDesc(String bookingRef, String recipientEmail);

    Page<Notification> findByType(String type, Pageable pageable);

    List<Notification> findBySentFalseAndRetryCountLessThan(int maxRetries);

    Page<Notification> findBySentFalseAndRetryCountLessThan(int maxRetries, Pageable pageable);

    long countBySentTrue();

    long countBySentFalse();

    boolean existsByBookingRefAndType(String bookingRef, String type);

    boolean existsByRecipientEmailAndType(String recipientEmail, String type);
}
