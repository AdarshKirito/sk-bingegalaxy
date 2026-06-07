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
import java.time.ZoneOffset;
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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BookingReminder> due = reminderRepo.findByFiredFalseAndCancelledFalseAndFireAtBefore(now);
        if (due.isEmpty()) return;

        log.info("Firing {} booking reminders", due.size());
        for (BookingReminder r : due) {
            try {
                String subject;
                String body;
                String type = r.getReminderType();
                switch (type == null ? "" : type) {
                    case "DAY_BEFORE" -> {
                        subject = "Reminder: Your booking is tomorrow! - " + r.getBookingRef();
                        body = buildDayBeforeBody(r);
                    }
                    case "TWO_HOURS_BEFORE" -> {
                        subject = "Reminder: Your booking starts in 2 hours - " + r.getBookingRef();
                        body = buildHoursBeforeBody(r, 2);
                    }
                    case "ONE_HOUR_BEFORE" -> {
                        subject = "Reminder: Your booking starts in 1 hour! - " + r.getBookingRef();
                        body = buildHoursBeforeBody(r, 1);
                    }
                    case "CHECK_IN_INSTRUCTIONS" -> {
                        subject = "Check-in details for your booking - " + r.getBookingRef();
                        body = buildCheckInInstructionsBody(r);
                    }
                    case "CANCELLATION_DEADLINE" -> {
                        subject = "Last chance to change your booking - " + r.getBookingRef();
                        body = buildCancellationDeadlineBody(r);
                    }
                    case "PAYMENT_PENDING" -> {
                        subject = "Complete payment for your booking - " + r.getBookingRef();
                        body = buildPaymentPendingBody(r);
                    }
                    case "POST_VISIT_REVIEW" -> {
                        subject = "How was your visit? - " + r.getBookingRef();
                        body = buildPostVisitReviewBody(r);
                    }
                    default -> {
                        subject = "Reminder for booking " + r.getBookingRef();
                        body = buildHoursBeforeBody(r, 1);
                    }
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
                        r.getRecipientPhoneCountryCode(),
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

    private String buildHoursBeforeBody(BookingReminder r, int hours) {
        return String.format("""
                Dear %s,
                
                Your booking starts in about %d hour%s!
                
                Booking Reference: %s
                Event: %s
                Date: %s
                Time: %s
                
                See you soon!
                
                Thank you,
                SK Binge Galaxy Team""",
                r.getRecipientName(), hours, hours == 1 ? "" : "s",
                r.getBookingRef(), r.getEventTypeName(),
                r.getBookingDate(), r.getStartTime());
    }

    private String buildCheckInInstructionsBody(BookingReminder r) {
        return String.format("""
                Dear %s,
                
                Your booking is coming up — here's how to check in smoothly:
                
                Booking Reference: %s
                Event: %s
                Date: %s
                Arrival window: from %s (we open the door 30 minutes before start time)
                
                When you arrive:
                  • Show this email — or your booking ref — at the front desk
                  • Or scan the QR code attached to your booking page
                  • Or share the 6-digit OTP we send when you arrive
                
                Tips:
                  • Please plan to be there ~10 minutes early to settle in
                  • Bring photo ID for the lead guest
                  • If you're running late, call us — late check-in is allowed up to 60 minutes after start
                
                Thank you,
                SK Binge Galaxy Team""",
                r.getRecipientName(), r.getBookingRef(), r.getEventTypeName(),
                r.getBookingDate(), r.getStartTime());
    }

    private String buildCancellationDeadlineBody(BookingReminder r) {
        return String.format("""
                Dear %s,
                
                A quick heads-up: the free cancellation window for your booking
                is closing soon.
                
                Booking Reference: %s
                Event: %s
                Date: %s, %s
                
                If you need to cancel or reschedule, please do so before the
                cancellation deadline. Changes after that may incur a fee per our
                cancellation policy.
                
                Need help? Reply to this email or call us.
                
                Thank you,
                SK Binge Galaxy Team""",
                r.getRecipientName(), r.getBookingRef(), r.getEventTypeName(),
                r.getBookingDate(), r.getStartTime());
    }

    private String buildPaymentPendingBody(BookingReminder r) {
        return String.format("""
                Dear %s,
                
                We noticed your booking is still awaiting payment.
                
                Booking Reference: %s
                Event: %s
                Date: %s, %s
                
                Please complete payment to confirm your slot — unpaid bookings
                may be released to other guests.
                
                Log in to finish payment, or contact us if you've already paid
                and need help reconciling.
                
                Thank you,
                SK Binge Galaxy Team""",
                r.getRecipientName(), r.getBookingRef(), r.getEventTypeName(),
                r.getBookingDate(), r.getStartTime());
    }

    private String buildPostVisitReviewBody(BookingReminder r) {
        return String.format("""
                Dear %s,
                
                Thank you for choosing SK Binge Galaxy! We hope you had a great
                time at your recent visit.
                
                Booking Reference: %s
                Event: %s
                Date: %s
                
                Your feedback helps us improve. Could you spare a minute to
                share your experience? Reply to this email with a rating from
                1–5 and a few words, or visit your bookings page to leave a
                review.
                
                Looking forward to welcoming you back soon.
                
                Thank you,
                SK Binge Galaxy Team""",
                r.getRecipientName(), r.getBookingRef(), r.getEventTypeName(),
                r.getBookingDate());
    }
}
