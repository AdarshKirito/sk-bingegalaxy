package com.skbingegalaxy.notification.config;

import com.skbingegalaxy.notification.model.BookingReminder;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.model.NotificationPreference;
import com.skbingegalaxy.notification.model.NotificationTemplate;
import com.skbingegalaxy.notification.model.PushSubscription;
import com.skbingegalaxy.notification.model.WhatsAppTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic index bootstrap for MongoDB.
 *
 * <p>Spring Boot 3 ships with {@code spring.data.mongodb.auto-index-creation}
 * defaulting to {@code false}, which left every {@code @Indexed} /
 * {@code @CompoundIndex} declaration inert — runtime-confirmed: live
 * collections carried only {@code _id_}. Concretely that meant:</p>
 * <ul>
 *   <li>the 90-day TTL on {@code notifications} never expired PII
 *       (recipient email/phone accumulating without bound), and</li>
 *   <li>the unique {@code (bookingRef, reminderType)} index on
 *       {@code booking_reminders} never enforced reminder dedup.</li>
 * </ul>
 *
 * <p>We enable the flag in config, but this runner is the guarantee: it
 * resolves each mapped entity's declared indexes and ensures them explicitly,
 * regardless of the global flag's state in any given environment. Duplicate
 * reminders are removed first (keeping the oldest per key) so the unique
 * index can always be created on a pre-existing dirty collection.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MongoIndexBootstrap implements ApplicationRunner {

    private static final List<Class<?>> INDEXED_MODELS = List.of(
        Notification.class,
        BookingReminder.class,
        NotificationTemplate.class,
        NotificationPreference.class,
        PushSubscription.class,
        WhatsAppTemplate.class);

    private final MongoTemplate mongoTemplate;
    private final MongoMappingContext mappingContext;

    @Override
    public void run(ApplicationArguments args) {
        dedupeBookingReminders();

        IndexResolver resolver = IndexResolver.create(mappingContext);
        for (Class<?> model : INDEXED_MODELS) {
            try {
                IndexOperations ops = mongoTemplate.indexOps(model);
                resolver.resolveIndexFor(model).forEach(ops::ensureIndex);
            } catch (Exception e) {
                // Loud but non-fatal: notification delivery still works without
                // the secondary indexes; the verification below flags the gap.
                log.error("Failed to ensure Mongo indexes for {}: {}", model.getSimpleName(), e.getMessage());
            }
        }

        verifyCriticalIndexes();
    }

    /**
     * Removes duplicate (bookingRef, reminderType) reminder rows, keeping the
     * oldest, so the unique compound index can be created even when the
     * collection accumulated duplicates while the index was inert.
     */
    private void dedupeBookingReminders() {
        try {
            var collection = mongoTemplate.getCollection(
                mongoTemplate.getCollectionName(BookingReminder.class));
            List<Document> dupGroups = collection.aggregate(List.of(
                new Document("$group", new Document("_id",
                        new Document("bookingRef", "$bookingRef").append("reminderType", "$reminderType"))
                    .append("ids", new Document("$push", "$_id"))
                    .append("n", new Document("$sum", 1))),
                new Document("$match", new Document("n", new Document("$gt", 1)))
            )).into(new java.util.ArrayList<>());

            int removed = 0;
            for (Document group : dupGroups) {
                List<?> ids = group.getList("ids", Object.class);
                // ObjectIds are time-ordered — dropping everything after the
                // first keeps the earliest reminder.
                for (Object id : ids.subList(1, ids.size())) {
                    collection.deleteOne(new Document("_id", id));
                    removed++;
                }
            }
            if (removed > 0) {
                log.warn("MongoIndexBootstrap: removed {} duplicate booking reminders across {} keys "
                    + "(accumulated while the unique index was inert)", removed, dupGroups.size());
            }
        } catch (Exception e) {
            log.error("MongoIndexBootstrap: reminder dedup failed — unique index creation may fail: {}",
                e.getMessage());
        }
    }

    /** Startup check demanded by the retention policy: alert if the TTL or unique index is missing. */
    private void verifyCriticalIndexes() {
        boolean ttlPresent = mongoTemplate.indexOps(Notification.class).getIndexInfo().stream()
            .anyMatch(ii -> ii.getExpireAfter().isPresent());
        boolean uniqueReminderPresent = mongoTemplate.indexOps(BookingReminder.class).getIndexInfo().stream()
            .anyMatch(ii -> ii.isUnique() && "idx_bookingRef_type".equals(ii.getName()));

        if (ttlPresent && uniqueReminderPresent) {
            log.info("MongoIndexBootstrap: TTL (notifications) and unique reminder indexes verified present");
        } else {
            log.error("MongoIndexBootstrap ALERT: critical indexes missing — notificationsTTL={} "
                + "uniqueReminderIndex={}. PII retention/dedup guarantees are NOT enforced.",
                ttlPresent, uniqueReminderPresent);
        }
    }
}
