package com.skbingegalaxy.notification.scheduler;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.model.BookingReminder;
import com.skbingegalaxy.notification.repository.BookingReminderRepository;
import com.skbingegalaxy.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Fires "your booking is tomorrow" and "starts in 1 hour" reminders.
 * ShedLock guarantees only one pod runs this at a time in a cluster.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingReminderScheduler {

    private final BookingReminderRepository reminderRepo;
    private final NotificationService notificationService;

    @Scheduled(fixedDelayString = "${app.reminder.check-interval-ms:60000}")
    @SchedulerLock(name = "BookingReminderScheduler", lockAtMostFor = "5m", lockAtLeastFor = "30s")
    public void fireReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<BookingReminder> due = reminderRepo.findByFiredFalseAndCancelledFalseAndFireAtBefore(now);
        if (due.isEmpty()) return;

        log.info("Firing {} booking reminders", due.size());
        for (BookingReminder r : due) {
            try {
                String subject;
                String body;
                if ("DAY_BEFORE".equals(r.getReminderType())) {
                    subject = "Reminder: Your booking is tomorrow! - " + r.getBookingRef();
                    body = buildDayBeforeBody(r);
                } else {
                    subject = "Reminder: Your booking starts in 1 hour! - " + r.getBookingRef();
                    body = buildOneHourBody(r);
                }

                Map<String, Object> meta = Map.of(
                        "reminderType", r.getReminderType(),
                        "bookingDate", r.getBookingDate().toString(),
                        "startTime", r.getStartTime().toString(),
                        "eventType", r.getEventTypeName() != null ? r.getEventTypeName() : ""
                );

                notificationService.sendNotification(
                        "BOOKING_REMINDER",
                        NotificationChannel.EMAIL,
                        r.getRecipientEmail(),
                        r.getRecipientPhone(),
                        r.getRecipientName(),
                        subject,
                        body,
                        r.getBookingRef(),
                        meta
                );

                r.setFired(true);
                reminderRepo.save(r);
            } catch (Exception e) {
                log.error("Failed to fire reminder {} for booking {}: {}", r.getId(), r.getBookingRef(), e.getMessage());
            }
        }
    }

    private String buildDayBeforeBody(BookingReminder r) {
        return String.format("""
                Dear %s,
                
                This is a friendly reminder that your booking is tomorrow!
                
                Booking Reference: %s
                Event: %s
                Date: %s
                Time: %s
                
                We look forward to hosting you!
                
                Thank you,
                SK Binge Galaxy Team""",
                r.getRecipientName(), r.getBookingRef(), r.getEventTypeName(),
                r.getBookingDate(), r.getStartTime());
    }

    private String buildOneHourBody(BookingReminder r) {
        return String.format("""
                Dear %s,
                
                Your booking starts in about 1 hour!
                
                Booking Reference: %s
                Event: %s
                Time: %s
                
                See you soon!
                
                Thank you,
                SK Binge Galaxy Team""",
                r.getRecipientName(), r.getBookingRef(), r.getEventTypeName(),
                r.getStartTime());
    }
}
