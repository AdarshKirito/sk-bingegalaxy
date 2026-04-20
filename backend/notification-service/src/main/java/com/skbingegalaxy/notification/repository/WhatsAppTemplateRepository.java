package com.skbingegalaxy.notification.repository;

import com.skbingegalaxy.notification.model.WhatsAppTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WhatsAppTemplateRepository extends MongoRepository<WhatsAppTemplate, String> {
    Optional<WhatsAppTemplate> findByTemplateNameAndActiveTrue(String templateName);
}
