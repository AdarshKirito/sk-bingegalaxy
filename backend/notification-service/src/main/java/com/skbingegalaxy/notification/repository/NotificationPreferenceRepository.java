package com.skbingegalaxy.notification.repository;

import com.skbingegalaxy.notification.model.NotificationPreference;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationPreferenceRepository extends MongoRepository<NotificationPreference, String> {
    Optional<NotificationPreference> findByRecipientEmail(String recipientEmail);
}
