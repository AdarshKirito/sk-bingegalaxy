package com.skbingegalaxy.notification.repository;

import com.skbingegalaxy.notification.model.NotificationTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationTemplateRepository extends MongoRepository<NotificationTemplate, String> {

    Optional<NotificationTemplate> findByNameAndChannelAndActiveTrue(String name, String channel);

    List<NotificationTemplate> findByNameAndChannelOrderByVersionDesc(String name, String channel);

    List<NotificationTemplate> findByNameOrderByVersionDesc(String name);

    Optional<NotificationTemplate> findByNameAndChannelAndVersion(String name, String channel, int version);
}
