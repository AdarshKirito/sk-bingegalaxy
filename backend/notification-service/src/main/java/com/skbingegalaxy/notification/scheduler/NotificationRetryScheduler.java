package com.skbingegalaxy.notification.scheduler;

import com.skbingegalaxy.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRetryScheduler {

    private final NotificationService notificationService;

    @Scheduled(
        initialDelayString = "${app.notification.retry-interval-ms:300000}",
        fixedDelayString = "${app.notification.retry-interval-ms:300000}")
    @SchedulerLock(name = "NotificationRetryScheduler", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void retryFailedNotifications() {
        log.debug("Running scheduled retry for failed notifications");
        notificationService.retryFailedNotifications();
    }
}