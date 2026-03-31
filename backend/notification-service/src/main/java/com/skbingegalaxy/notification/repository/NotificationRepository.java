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

    Page<Notification> findByType(String type, Pageable pageable);

    List<Notification> findBySentFalseAndRetryCountLessThan(int maxRetries);

    long countBySentTrue();

    long countBySentFalse();
}
