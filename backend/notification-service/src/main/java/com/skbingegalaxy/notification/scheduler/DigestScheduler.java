package com.skbingegalaxy.notification.scheduler;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import com.skbingegalaxy.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Periodically collects sent notifications tagged with a digest group
 * and sends a single summary email per recipient.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DigestScheduler {

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    @Value("${app.notification.digest-interval-ms:3600000}")
    private long digestIntervalMs;

    @Scheduled(fixedDelayString = "${app.notification.digest-interval-ms:3600000}")
    @SchedulerLock(name = "DigestScheduler", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void sendDigests() {
        // Find all un-digested, sent notifications with a non-null digestGroup
        var grouped = notificationRepository.findUndigestedWithDigestGroup().stream()
                .collect(Collectors.groupingBy(Notification::getDigestGroup));

        if (grouped.isEmpty()) return;

        log.info("Processing {} digest groups", grouped.size());

        for (Map.Entry<String, List<Notification>> entry : grouped.entrySet()) {
            String digestGroup = entry.getKey();
            List<Notification> notifications = entry.getValue();

            if (notifications.size() < 2) {
                // Not worth batching a single notification
                continue;
            }

            String recipientEmail = notifications.get(0).getRecipientEmail();
            String recipientName = notifications.get(0).getRecipientName();

            StringBuilder digestBody = new StringBuilder();
            digestBody.append(String.format("Hi %s,\n\nHere's a summary of your recent notifications:\n\n",
                    recipientName != null ? recipientName : "there"));

            for (Notification n : notifications) {
                digestBody.append(String.format("• [%s] %s\n", n.getType(),
                        n.getSubject() != null ? n.getSubject() : n.getBody()));
            }
            digestBody.append("\nThank you,\nSK Binge Galaxy Team");

            try {
                notificationService.sendNotification(
                        "DIGEST",
                        NotificationChannel.EMAIL,
                        recipientEmail,
                        null,
                        recipientName,
                        "Your notification digest — " + notifications.size() + " updates",
                        digestBody.toString(),
                        null,
                        Map.of("digestCount", String.valueOf(notifications.size()))
                );

                // Mark all as digested (batch save)
                notifications.forEach(n -> n.setDigested(true));
                notificationRepository.saveAll(notifications);

                log.info("Sent digest email to {} with {} notifications", recipientEmail, notifications.size());
            } catch (Exception e) {
                log.error("Failed to send digest for group {}: {}", digestGroup, e.getMessage());
            }
        }
    }
}
