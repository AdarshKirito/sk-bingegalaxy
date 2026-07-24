package com.skbingegalaxy.notification.repository;

import com.skbingegalaxy.notification.model.PushSubscription;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends MongoRepository<PushSubscription, String> {

    List<PushSubscription> findByRecipientEmail(String recipientEmail);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    void deleteByEndpoint(String endpoint);

    long countByRecipientEmail(String recipientEmail);
}
