package com.skbingegalaxy.notification.listener;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.UserAnonymizedEvent;
import com.skbingegalaxy.notification.model.BookingReminder;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.model.NotificationPreference;
import com.skbingegalaxy.notification.model.PushSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Right-to-erasure fan-out (DATA-004): removes every notification-side record
 * keyed by the anonymized user's email. Unlike booking/payment (which must
 * tombstone financial rows), notification documents have no retention
 * obligation — deletion is the correct erasure here:
 *
 * <ul>
 *   <li>{@code notifications} — delivered message log (email/phone/name)</li>
 *   <li>{@code booking_reminders} — scheduled reminders (contact snapshot)</li>
 *   <li>{@code push_subscriptions} — browser push endpoints for the user</li>
 *   <li>{@code notification_preferences} — the user's channel preferences</li>
 * </ul>
 *
 * Idempotent: re-delivery deletes zero documents the second time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserAnonymizedEventListener {

    private final MongoTemplate mongoTemplate;

    @KafkaListener(topics = KafkaTopics.USER_ANONYMIZED, groupId = "notification-service")
    public void onUserAnonymized(UserAnonymizedEvent event) {
        if (event == null || event.getOriginalEmail() == null || event.getOriginalEmail().isBlank()) {
            log.warn("user.anonymized event without originalEmail — notification-side erasure skipped");
            return;
        }
        Query byEmail = Query.query(Criteria.where("recipientEmail").is(event.getOriginalEmail()));

        long notifications = mongoTemplate.remove(byEmail, Notification.class).getDeletedCount();
        long reminders = mongoTemplate.remove(byEmail, BookingReminder.class).getDeletedCount();
        long pushSubs = mongoTemplate.remove(byEmail, PushSubscription.class).getDeletedCount();
        long prefs = mongoTemplate.remove(byEmail, NotificationPreference.class).getDeletedCount();

        log.info("Erasure for anonymized user {}: notifications={} reminders={} pushSubscriptions={} preferences={}",
            event.getUserId(), notifications, reminders, pushSubs, prefs);
    }
}
