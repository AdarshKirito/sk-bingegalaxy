package com.skbingegalaxy.notification.config;

import com.mongodb.client.MongoClient;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures ShedLock with a MongoDB-backed lock provider so that
 * {@code @SchedulerLock} annotations prevent concurrent execution
 * across multiple notification-service pods.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(
            MongoClient mongoClient,
            @Value("${spring.data.mongodb.database:notification_db}") String databaseName) {
        return new MongoLockProvider(mongoClient.getDatabase(databaseName));
    }
}
