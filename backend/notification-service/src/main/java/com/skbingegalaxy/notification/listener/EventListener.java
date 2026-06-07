package com.skbingegalaxy.notification.listener;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.BookingEvent;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.event.PaymentEvent;
import com.skbingegalaxy.notification.model.BookingReminder;
import com.skbingegalaxy.notification.repository.BookingReminderRepository;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import com.skbingegalaxy.notification.service.ChannelRouter;
import com.skbingegalaxy.notification.service.EmailRateLimiter;
import com.skbingegalaxy.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventListener {

    /** Only suppress duplicates if a successfully-sent notification exists within this window. */
    private static final long DEDUP_TTL_HOURS = 1;

    /** Max time a Kafka consumer thread will wait for an email rate-limit slot before dropping. */
    private static final long EMAIL_RATE_LIMIT_WAIT_MS = 30_000;

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final BookingReminderRepository bookingReminderRepository;
    private final ChannelRouter channelRouter;
    private final EmailRateLimiter emailRateLimiter;

    /**
     * Acquire one email rate-limit slot before sending. Returns false (and logs a warning)
     * if the per-minute quota is exhausted after waiting EMAIL_RATE_LIMIT_WAIT_MS.
     * Non-EMAIL channels bypass the gate — they have their own provider-level limits.
     */
    private boolean acquireEmailSlot(NotificationChannel channel, String context) {
        if (channel != NotificationChannel.EMAIL) return true;
        boolean acquired = emailRateLimiter.tryAcquire(EMAIL_RATE_LIMIT_WAIT_MS);
        if (!acquired) {
            log.error("Email rate limit exceeded — dropping notification for {}. "
                + "Remaining permits={}. Investigate bulk-cancel storm or raise EMAIL_RATE_PER_MINUTE.",
                context, emailRateLimiter.availablePermits());
        }
        return acquired;
    }

    private boolean recentlySentForBooking(String bookingRef, String type) {
        return notificationRepository.existsByBookingRefAndTypeAndSentTrueAndCreatedAtAfter(
            bookingRef, type, LocalDateTime.now(ZoneOffset.UTC).minusHours(DEDUP_TTL_HOURS));
    }

    private boolean recentlySentForEmail(String email, String type) {
        return notificationRepository.existsByRecipientEmailAndTypeAndSentTrueAndCreatedAtAfter(
            email, type, LocalDateTime.now(ZoneOffset.UTC).minusHours(DEDUP_TTL_HOURS));
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CREATED, groupId = "notification-service")
    public void handleBookingCreated(BookingEvent event) {
        try {
            log.info("Booking created event: {}", event.getBookingRef());
            if (event.getCustomerEmail() == null || event.getBookingRef() == null) {
                log.warn("Skipping BOOKING_CREATED — missing required fields");
                return;
            }
            if (recentlySentForBooking(event.getBookingRef(), "BOOKING_CREATED")) {
                log.info("Duplicate BOOKING_CREATED for {} — skipping (sent within TTL)", event.getBookingRef());
                return;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("eventType", event.getEventTypeName());
            meta.put("bookingDate", event.getBookingDate() != null ? event.getBookingDate().toString() : "");
            meta.put("startTime", event.getStartTime() != null ? event.getStartTime().toString() : "");
            meta.put("durationHours", event.getDurationHours());
            meta.put("totalAmount", event.getTotalAmount() != null ? event.getTotalAmount().toPlainString() : "0");
            NotificationChannel channel = channelRouter.resolveChannel(
                    event.getCustomerEmail(), event.getCustomerPhone(), "BOOKING_CREATED", meta);
            if (!acquireEmailSlot(channel, "BOOKING_CREATED:" + event.getBookingRef())) return;
            notificationService.sendNotification(
                "BOOKING_CREATED",
                channel,
                event.getCustomerEmail(),
                event.getCustomerPhone(),
                event.getCustomerPhoneCountryCode(),
                event.getCustomerName(),
                "Booking Confirmed - " + event.getBookingRef(),
                buildBookingCreatedBody(event),
                event.getBookingRef(),
                meta
            );

            // ── Schedule reminders ("day before" + "1 hour before") ──
            scheduleReminders(event);
        } catch (Exception e) {
            log.error("Failed to process BOOKING_CREATED event for {}: {}", event.getBookingRef(), e.getMessage(), e);
            // Rethrow so DefaultErrorHandler can retry and route to DLT after exhausted retries
            throw new RuntimeException("Failed to process BOOKING_CREATED for " + event.getBookingRef(), e);
        }
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = "notification-service")
    public void handleBookingCancelled(BookingEvent event) {
        try {
            log.info("Booking cancelled event: {} (status={})", event.getBookingRef(), event.getStatus());
            if (event.getCustomerEmail() == null || event.getBookingRef() == null) {
                log.warn("Skipping BOOKING_CANCELLED — missing required fields");
                return;
            }

            // Audit-driven NO_SHOW path: booking-service publishes here so we
            // can cancel scheduled reminders (notably POST_VISIT_REVIEW) but
            // we MUST NOT email the customer telling them their booking was
            // cancelled — they simply didn't show up. Run the side-effect and
            // exit early.
            if ("NO_SHOW".equalsIgnoreCase(event.getStatus())) {
                cancelReminders(event.getBookingRef());
                log.info("NO_SHOW audit for {} — reminders cancelled, suppressing customer email",
                    event.getBookingRef());
                return;
            }

            if (recentlySentForBooking(event.getBookingRef(), "BOOKING_CANCELLED")) {
                log.info("Duplicate BOOKING_CANCELLED for {} — skipping (sent within TTL)", event.getBookingRef());
                return;
            }
            Map<String, Object> cancelMeta = Map.of("eventType", event.getEventTypeName() != null ? event.getEventTypeName() : "");
            NotificationChannel channel = channelRouter.resolveChannel(
                    event.getCustomerEmail(), event.getCustomerPhone(), "BOOKING_CANCELLED", cancelMeta);
            if (!acquireEmailSlot(channel, "BOOKING_CANCELLED:" + event.getBookingRef())) return;
            notificationService.sendNotification(
                "BOOKING_CANCELLED",
                channel,
                event.getCustomerEmail(),
                event.getCustomerPhone(),
                event.getCustomerPhoneCountryCode(),
                event.getCustomerName(),
                "Booking Cancelled - " + event.getBookingRef(),
                buildBookingCancelledBody(event),
                event.getBookingRef(),
                cancelMeta
            );

            // ── Cancel pending reminders for this booking ──
            cancelReminders(event.getBookingRef());
        } catch (Exception e) {
            log.error("Failed to process BOOKING_CANCELLED event for {}: {}", event.getBookingRef(), e.getMessage(), e);
            throw new RuntimeException("Failed to process BOOKING_CANCELLED for " + event.getBookingRef(), e);
        }
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCESS, groupId = "notification-service")
    public void handlePaymentSuccess(PaymentEvent event) {
        try {
            log.info("Payment success event: {}", event.getBookingRef());
            if (event.getCustomerEmail() == null) {
                log.warn("Skipping PAYMENT_SUCCESS — missing recipient email");
                return;
            }
            if (recentlySentForBooking(event.getBookingRef(), "PAYMENT_SUCCESS")) {
                log.info("Duplicate PAYMENT_SUCCESS for {} — skipping (sent within TTL)", event.getBookingRef());
                return;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("transactionId", event.getTransactionId() != null ? event.getTransactionId() : "");
            meta.put("amount", event.getAmount() != null ? event.getAmount().toPlainString() : "0");
            meta.put("paymentMethod", event.getPaymentMethod() != null ? event.getPaymentMethod() : "");
            NotificationChannel channel = channelRouter.resolveChannel(
                    event.getCustomerEmail(), event.getCustomerPhone(), "PAYMENT_SUCCESS", meta);
            if (!acquireEmailSlot(channel, "PAYMENT_SUCCESS:" + event.getBookingRef())) return;
            notificationService.sendNotification(
                "PAYMENT_SUCCESS",
                channel,
                event.getCustomerEmail(), event.getCustomerPhone(), event.getCustomerPhoneCountryCode(),
                event.getCustomerName(),
                "Payment Successful - " + event.getBookingRef(),
                buildPaymentSuccessBody(event),
                event.getBookingRef(),
                meta
            );

            // Once payment succeeds, the PAYMENT_PENDING nudge is no longer relevant.
            cancelRemindersOfType(event.getBookingRef(), "PAYMENT_PENDING");
        } catch (Exception e) {
            log.error("Failed to process PAYMENT_SUCCESS event for {}: {}", event.getBookingRef(), e.getMessage(), e);
            throw new RuntimeException("Failed to process PAYMENT_SUCCESS for " + event.getBookingRef(), e);
        }
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "notification-service")
    public void handlePaymentFailed(PaymentEvent event) {
        try {
            log.info("Payment failed event: {}", event.getBookingRef());
            if (event.getCustomerEmail() == null) {
                log.warn("Skipping PAYMENT_FAILED — missing recipient email");
                return;
            }
            if (recentlySentForBooking(event.getBookingRef(), "PAYMENT_FAILED")) {
                log.info("Duplicate PAYMENT_FAILED for {} — skipping (sent within TTL)", event.getBookingRef());
                return;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("transactionId", event.getTransactionId() != null ? event.getTransactionId() : "");
            NotificationChannel channel = channelRouter.resolveChannel(
                    event.getCustomerEmail(), event.getCustomerPhone(), "PAYMENT_FAILED", meta);
            if (!acquireEmailSlot(channel, "PAYMENT_FAILED:" + event.getBookingRef())) return;
            notificationService.sendNotification(
                "PAYMENT_FAILED",
                channel,
                event.getCustomerEmail(), event.getCustomerPhone(), event.getCustomerPhoneCountryCode(),
                event.getCustomerName(),
                "Payment Failed - " + event.getBookingRef(),
                buildPaymentFailedBody(event),
                event.getBookingRef(),
                meta
            );
        } catch (Exception e) {
            log.error("Failed to process PAYMENT_FAILED event for {}: {}", event.getBookingRef(), e.getMessage(), e);
            throw new RuntimeException("Failed to process PAYMENT_FAILED for " + event.getBookingRef(), e);
        }
    }

    @KafkaListener(topics = KafkaTopics.NOTIFICATION_SEND, groupId = "notification-service")
    public void handleDirectNotification(NotificationEvent event) {
        try {
            // Producers (e.g. auth-service) often populate `templateName`+`templateData`
            // instead of `type`+`subject`+`body`. Normalize here so downstream
            // template resolution + DB dedup keys work, and a null Subject never
            // reaches JavaMail ("Subject must not be null" IllegalArgumentException).
            String resolvedType = event.getType();
            if (resolvedType == null && event.getTemplateName() != null) {
                resolvedType = mapTemplateNameToType(event.getTemplateName());
            }
            String resolvedSubject = event.getSubject();
            if (resolvedSubject == null || resolvedSubject.isBlank()) {
                resolvedSubject = defaultSubjectFor(resolvedType, event.getTemplateName());
            }
            Map<String, Object> metadata = event.getMetadata();
            if ((metadata == null || metadata.isEmpty()) && event.getTemplateData() != null) {
                metadata = new HashMap<>(event.getTemplateData());
            }

            log.info("Direct notification event: type={}, template={}, to={}",
                resolvedType, event.getTemplateName(), event.getRecipientEmail());
            if (event.getRecipientEmail() != null && resolvedType != null
                    && recentlySentForEmail(event.getRecipientEmail(), resolvedType)) {
                log.info("Duplicate NOTIFICATION_SEND ({}) for {} — skipping", resolvedType, event.getRecipientEmail());
                return;
            }
            NotificationChannel channel = event.getChannel() != null ? event.getChannel() : NotificationChannel.EMAIL;
            if (!acquireEmailSlot(channel, "DIRECT:" + resolvedType + ":" + event.getRecipientEmail())) return;
            notificationService.sendNotification(
                resolvedType,
                channel,
                event.getRecipientEmail(),
                event.getRecipientPhone(),
                event.getRecipientPhoneCountryCode(),
                event.getRecipientName(),
                resolvedSubject,
                event.getBody(),
                event.getBookingRef(),
                metadata
            );
        } catch (Exception e) {
            log.error("Failed to process NOTIFICATION_SEND event: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process NOTIFICATION_SEND event", e);
        }
    }

    /**
     * Map producer-side template aliases to internal notification types.
     * Keeps producers decoupled from our {@code TEMPLATE_MAP} keys.
     */
    private static String mapTemplateNameToType(String templateName) {
        if (templateName == null) return null;
        switch (templateName.toUpperCase()) {
            case "WELCOME": return "USER_REGISTERED";
            case "PASSWORD_RESET": return "PASSWORD_RESET";
            case "BOOKING_CREATED": return "BOOKING_CREATED";
            case "BOOKING_CANCELLED": return "BOOKING_CANCELLED";
            case "BOOKING_REMINDER": return "BOOKING_REMINDER";
            case "PAYMENT_SUCCESS": return "PAYMENT_SUCCESS";
            case "PAYMENT_FAILED": return "PAYMENT_FAILED";
            default: return templateName.toUpperCase();
        }
    }

    private static String defaultSubjectFor(String type, String templateName) {
        String key = type != null ? type : templateName;
        if (key == null) return "SK Binge Galaxy";
        switch (key.toUpperCase()) {
            case "USER_REGISTERED":
            case "WELCOME":           return "Welcome to SK Binge Galaxy";
            case "PASSWORD_RESET":    return "Reset your SK Binge Galaxy password";
            case "BOOKING_CREATED":   return "Your booking is confirmed";
            case "BOOKING_CANCELLED": return "Your booking was cancelled";
            case "BOOKING_REMINDER":  return "Reminder: your upcoming booking";
            case "PAYMENT_SUCCESS":   return "Payment received — thank you!";
            case "PAYMENT_FAILED":    return "Action required: payment failed";
            default:                  return "SK Binge Galaxy";
        }
    }

    @KafkaListener(topics = KafkaTopics.USER_REGISTERED, groupId = "notification-service")
    public void handleUserRegistered(NotificationEvent event) {
        try {
            log.info("User registered event: {}", event.getRecipientEmail());
            if (event.getRecipientEmail() == null) {
                log.warn("Skipping USER_REGISTERED — missing recipient email");
                return;
            }
            if (recentlySentForEmail(event.getRecipientEmail(), "USER_REGISTERED")) {
                log.info("Duplicate USER_REGISTERED for {} — skipping (sent within TTL)", event.getRecipientEmail());
                return;
            }
            NotificationChannel channel = channelRouter.resolveChannel(
                    event.getRecipientEmail(), event.getRecipientPhone(), "USER_REGISTERED", null);
            if (!acquireEmailSlot(channel, "USER_REGISTERED:" + event.getRecipientEmail())) return;
            notificationService.sendNotification(
                "USER_REGISTERED",
                channel,
                event.getRecipientEmail(),
                event.getRecipientPhone(),
                event.getRecipientPhoneCountryCode(),
                event.getRecipientName(),
                "Welcome to SK Binge Galaxy!",
                buildWelcomeBody(event),
                null,
                null
            );
        } catch (Exception e) {
            log.error("Failed to process USER_REGISTERED event for {}: {}", event.getRecipientEmail(), e.getMessage(), e);
            throw new RuntimeException("Failed to process USER_REGISTERED for " + event.getRecipientEmail(), e);
        }
    }

    @KafkaListener(topics = KafkaTopics.PASSWORD_RESET, groupId = "notification-service")
    public void handlePasswordReset(NotificationEvent event) {
        try {
            log.info("Password reset event: {}", event.getRecipientEmail());
            if (event.getRecipientEmail() == null) {
                log.warn("Skipping PASSWORD_RESET — missing recipient email");
                return;
            }
            if (recentlySentForEmail(event.getRecipientEmail(), "PASSWORD_RESET")) {
                log.info("Duplicate PASSWORD_RESET for {} — skipping (sent within TTL)", event.getRecipientEmail());
                return;
            }
            Map<String, Object> meta = event.getMetadata() != null ? new HashMap<>(event.getMetadata()) : new HashMap<>();
            meta.put("name", event.getRecipientName());
            NotificationChannel channel = channelRouter.resolveChannel(
                    event.getRecipientEmail(), null, "PASSWORD_RESET", meta);
            if (!acquireEmailSlot(channel, "PASSWORD_RESET:" + event.getRecipientEmail())) return;
            notificationService.sendNotification(
                "PASSWORD_RESET",
                channel,
                event.getRecipientEmail(),
                null,
                null,
                event.getRecipientName(),
                "Password Reset Request - SK Binge Galaxy",
                event.getBody(),
                null,
                meta
            );
        } catch (Exception e) {
            log.error("Failed to process PASSWORD_RESET event for {}: {}", event.getRecipientEmail(), e.getMessage(), e);
            throw new RuntimeException("Failed to process PASSWORD_RESET for " + event.getRecipientEmail(), e);
        }
    }

    // ---- Body builders ----

    private String buildBookingCreatedBody(BookingEvent event) {
        return String.format("""
            Dear %s,
            
            Your booking has been confirmed!
            
            Booking Reference: %s
            Event Type: %s
            Date: %s
            Start Time: %s
            Duration: %d hours
            Total Amount: ₹%.2f
            
            Please complete payment to secure your reservation.
            
            Thank you,
            SK Binge Galaxy Team""",
            event.getCustomerName(),
            event.getBookingRef(),
            event.getEventTypeName(),
            event.getBookingDate(),
            event.getStartTime(),
            event.getDurationHours(),
            event.getTotalAmount().doubleValue()
        );
    }

    private String buildBookingCancelledBody(BookingEvent event) {
        return String.format("""
            Dear %s,
            
            Your booking %s has been cancelled.
            
            If you paid for this booking, a refund will be processed within 5-7 business days.
            
            If you did not request this cancellation, please contact us immediately.
            
            Thank you,
            SK Binge Galaxy Team""",
            event.getCustomerName(),
            event.getBookingRef()
        );
    }

    private String buildPaymentSuccessBody(PaymentEvent event) {
        return String.format("""
            Payment Successful!
            
            Booking Reference: %s
            Transaction ID: %s
            Amount: ₹%.2f
            Payment Method: %s
            
            Your booking is now fully confirmed. We look forward to hosting you!
            
            Thank you,
            SK Binge Galaxy Team""",
            event.getBookingRef(),
            event.getTransactionId(),
            event.getAmount().doubleValue(),
            event.getPaymentMethod()
        );
    }

    private String buildPaymentFailedBody(PaymentEvent event) {
        return String.format("""
            Payment Failed
            
            Booking Reference: %s
            Transaction ID: %s
            
            Your payment could not be processed. Please try again or use a different payment method.
            
            If the amount was debited from your account, it will be refunded within 5-7 business days.
            
            Thank you,
            SK Binge Galaxy Team""",
            event.getBookingRef(),
            event.getTransactionId()
        );
    }

    private String buildWelcomeBody(NotificationEvent event) {
        return String.format("""
            Dear %s,
            
            Welcome to SK Binge Galaxy!
            
            Your account has been created successfully. You can now:
            - Browse available dates and time slots
            - Book private theater experiences
            - Choose from various event types and add-ons
            
            Start your first booking today!
            
            Thank you,
            SK Binge Galaxy Team""",
            event.getRecipientName()
        );
    }

    // ── Scheduled-reminder helpers ──────────────────────────────

    private void scheduleReminders(BookingEvent event) {
        if (event.getBookingDate() == null || event.getStartTime() == null) {
            log.warn("Cannot schedule reminders for {} — missing date/time", event.getBookingRef());
            return;
        }
        try {
            LocalDateTime bookingStart = LocalDateTime.of(event.getBookingDate(), event.getStartTime());
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            // "Day before" reminder — fires at 10:00 the day before the booking
            LocalDateTime dayBefore = LocalDateTime.of(
                    event.getBookingDate().minusDays(1), LocalTime.of(10, 0));
            saveReminder(event, "DAY_BEFORE", dayBefore, now);

            // "Check-in instructions" — fires ~3 hours before start (venue, parking, dress code)
            LocalDateTime checkInInstructions = bookingStart.minusHours(3);
            saveReminder(event, "CHECK_IN_INSTRUCTIONS", checkInInstructions, now);

            // "2 hours before" — primary heads-up reminder (replaces the previous 1-hour version)
            LocalDateTime twoHoursBefore = bookingStart.minusHours(2);
            saveReminder(event, "TWO_HOURS_BEFORE", twoHoursBefore, now);

            // "1 hour before" — kept as a final nudge for last-minute guests
            LocalDateTime oneHourBefore = bookingStart.minusHours(1);
            saveReminder(event, "ONE_HOUR_BEFORE", oneHourBefore, now);

            // "Cancellation deadline" — fires 6 hours before the per-binge
            // cancellation cut-off. The cut-off is carried on the BookingEvent
            // (in MINUTES, populated by booking-service from binge.customerCancellationCutoffMinutes).
            // Falls back to the env override CANCELLATION_HOURS_BEFORE (legacy)
            // and finally a 180-minute default to match the booking-service entity default.
            int cutoffMinutes;
            if (event.getCustomerCancellationCutoffMinutes() != null
                    && event.getCustomerCancellationCutoffMinutes() > 0) {
                cutoffMinutes = event.getCustomerCancellationCutoffMinutes();
            } else {
                int legacyHours = parseInt(System.getenv("CANCELLATION_HOURS_BEFORE"), 0);
                cutoffMinutes = legacyHours > 0 ? legacyHours * 60 : 180;
            }
            LocalDateTime deadline = bookingStart.minusMinutes(cutoffMinutes);
            LocalDateTime deadlineReminder = deadline.minusHours(6);
            saveReminder(event, "CANCELLATION_DEADLINE", deadlineReminder, now);

            // "Payment pending" — fires 30 minutes after creation if the booking
            // is still PENDING. The downstream PAYMENT_SUCCESS / BOOKING_CANCELLED
            // listeners cancel this reminder if it becomes irrelevant.
            saveReminder(event, "PAYMENT_PENDING", now.plusMinutes(30), now);

            // "Post-visit review" — fires (booking end + 4h) by default. The
            // post-visit window is configurable via the env var
            // POST_VISIT_REVIEW_HOURS_AFTER (default 4). Cancelled if the
            // booking ends up CANCELLED / NO_SHOW (handled by cancelReminders).
            int reviewDelayHours = parseInt(System.getenv("POST_VISIT_REVIEW_HOURS_AFTER"), 4);
            int durationMinutes = event.getDurationMinutes() != null
                ? event.getDurationMinutes()
                : event.getDurationHours() * 60;
            LocalDateTime bookingEnd = bookingStart.plusMinutes(durationMinutes);
            saveReminder(event, "POST_VISIT_REVIEW", bookingEnd.plusHours(reviewDelayHours), now);

            log.info("Scheduled reminders for booking {}", event.getBookingRef());
        } catch (Exception e) {
            log.error("Failed to schedule reminders for {}: {}", event.getBookingRef(), e.getMessage());
        }
    }

    private void saveReminder(BookingEvent event, String type, LocalDateTime fireAt, LocalDateTime now) {
        if (!fireAt.isAfter(now)) return; // skip past-due
        try {
            bookingReminderRepository.save(BookingReminder.builder()
                    .bookingRef(event.getBookingRef())
                    .recipientEmail(event.getCustomerEmail())
                    .recipientPhone(event.getCustomerPhone())
                    .recipientPhoneCountryCode(event.getCustomerPhoneCountryCode())
                    .recipientName(event.getCustomerName())
                    .eventTypeName(event.getEventTypeName())
                    .bookingDate(event.getBookingDate())
                    .startTime(event.getStartTime())
                    .durationHours(event.getDurationHours())
                    .reminderType(type)
                    .fireAt(fireAt)
                    .build());
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // Idempotent: same (bookingRef, type) already scheduled — fine.
            log.debug("Reminder {} for {} already exists — skipping", type, event.getBookingRef());
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return s == null ? fallback : Integer.parseInt(s); }
        catch (NumberFormatException e) { return fallback; }
    }

    private void cancelReminders(String bookingRef) {
        try {
            var reminders = bookingReminderRepository.findByBookingRef(bookingRef);
            var pending = reminders.stream().filter(r -> !r.isFired()).toList();
            if (!pending.isEmpty()) {
                pending.forEach(r -> r.setCancelled(true));
                bookingReminderRepository.saveAll(pending);
                log.info("Cancelled {} pending reminders for booking {}", pending.size(), bookingRef);
            }
        } catch (Exception e) {
            log.error("Failed to cancel reminders for {}: {}", bookingRef, e.getMessage());
        }
    }

    private void cancelRemindersOfType(String bookingRef, String reminderType) {
        try {
            var matched = bookingReminderRepository
                .findByBookingRefAndReminderType(bookingRef, reminderType)
                .stream().filter(r -> !r.isFired() && !r.isCancelled()).toList();
            if (!matched.isEmpty()) {
                matched.forEach(r -> r.setCancelled(true));
                bookingReminderRepository.saveAll(matched);
                log.info("Cancelled {} {} reminder(s) for booking {}",
                    matched.size(), reminderType, bookingRef);
            }
        } catch (Exception e) {
            log.error("Failed to cancel {} reminder for {}: {}",
                reminderType, bookingRef, e.getMessage());
        }
    }
}
