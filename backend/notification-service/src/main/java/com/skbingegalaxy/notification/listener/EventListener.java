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
import com.skbingegalaxy.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventListener {

    /** Only suppress duplicates if a successfully-sent notification exists within this window. */
    private static final long DEDUP_TTL_HOURS = 1;

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final BookingReminderRepository bookingReminderRepository;
    private final ChannelRouter channelRouter;

    private boolean recentlySentForBooking(String bookingRef, String type) {
        return notificationRepository.existsByBookingRefAndTypeAndSentTrueAndCreatedAtAfter(
            bookingRef, type, LocalDateTime.now().minusHours(DEDUP_TTL_HOURS));
    }

    private boolean recentlySentForEmail(String email, String type) {
        return notificationRepository.existsByRecipientEmailAndTypeAndSentTrueAndCreatedAtAfter(
            email, type, LocalDateTime.now().minusHours(DEDUP_TTL_HOURS));
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
            notificationService.sendNotification(
                "BOOKING_CREATED",
                channel,
                event.getCustomerEmail(),
                event.getCustomerPhone(),
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
            log.info("Booking cancelled event: {}", event.getBookingRef());
            if (event.getCustomerEmail() == null || event.getBookingRef() == null) {
                log.warn("Skipping BOOKING_CANCELLED — missing required fields");
                return;
            }
            if (recentlySentForBooking(event.getBookingRef(), "BOOKING_CANCELLED")) {
                log.info("Duplicate BOOKING_CANCELLED for {} — skipping (sent within TTL)", event.getBookingRef());
                return;
            }
            Map<String, Object> cancelMeta = Map.of("eventType", event.getEventTypeName() != null ? event.getEventTypeName() : "");
            NotificationChannel channel = channelRouter.resolveChannel(
                    event.getCustomerEmail(), event.getCustomerPhone(), "BOOKING_CANCELLED", cancelMeta);
            notificationService.sendNotification(
                "BOOKING_CANCELLED",
                channel,
                event.getCustomerEmail(),
                event.getCustomerPhone(),
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
            notificationService.sendNotification(
                "PAYMENT_SUCCESS",
                channel,
                event.getCustomerEmail(), event.getCustomerPhone(), event.getCustomerName(),
                "Payment Successful - " + event.getBookingRef(),
                buildPaymentSuccessBody(event),
                event.getBookingRef(),
                meta
            );
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
            notificationService.sendNotification(
                "PAYMENT_FAILED",
                channel,
                event.getCustomerEmail(), event.getCustomerPhone(), event.getCustomerName(),
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
            log.info("Direct notification event: type={}, to={}", event.getType(), event.getRecipientEmail());
            if (event.getRecipientEmail() != null && event.getType() != null
                    && recentlySentForEmail(event.getRecipientEmail(), event.getType())) {
                log.info("Duplicate NOTIFICATION_SEND ({}) for {} — skipping", event.getType(), event.getRecipientEmail());
                return;
            }
            NotificationChannel channel = event.getChannel() != null ? event.getChannel() : NotificationChannel.EMAIL;

            notificationService.sendNotification(
                event.getType(),
                channel,
                event.getRecipientEmail(),
                event.getRecipientPhone(),
                event.getRecipientName(),
                event.getSubject(),
                event.getBody(),
                event.getBookingRef(),
                event.getMetadata()
            );
        } catch (Exception e) {
            log.error("Failed to process NOTIFICATION_SEND event: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process NOTIFICATION_SEND event", e);
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
            notificationService.sendNotification(
                "USER_REGISTERED",
                channel,
                event.getRecipientEmail(),
                event.getRecipientPhone(),
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
            notificationService.sendNotification(
                "PASSWORD_RESET",
                channel,
                event.getRecipientEmail(),
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

            // "Day before" reminder — fires at 10:00 the day before the booking
            LocalDateTime dayBefore = LocalDateTime.of(
                    event.getBookingDate().minusDays(1), LocalTime.of(10, 0));
            if (dayBefore.isAfter(LocalDateTime.now())) {
                bookingReminderRepository.save(BookingReminder.builder()
                        .bookingRef(event.getBookingRef())
                        .recipientEmail(event.getCustomerEmail())
                        .recipientPhone(event.getCustomerPhone())
                        .recipientName(event.getCustomerName())
                        .eventTypeName(event.getEventTypeName())
                        .bookingDate(event.getBookingDate())
                        .startTime(event.getStartTime())
                        .durationHours(event.getDurationHours())
                        .reminderType("DAY_BEFORE")
                        .fireAt(dayBefore)
                        .build());
            }

            // "1 hour before" reminder
            LocalDateTime oneHourBefore = bookingStart.minusHours(1);
            if (oneHourBefore.isAfter(LocalDateTime.now())) {
                bookingReminderRepository.save(BookingReminder.builder()
                        .bookingRef(event.getBookingRef())
                        .recipientEmail(event.getCustomerEmail())
                        .recipientPhone(event.getCustomerPhone())
                        .recipientName(event.getCustomerName())
                        .eventTypeName(event.getEventTypeName())
                        .bookingDate(event.getBookingDate())
                        .startTime(event.getStartTime())
                        .durationHours(event.getDurationHours())
                        .reminderType("ONE_HOUR_BEFORE")
                        .fireAt(oneHourBefore)
                        .build());
            }

            log.info("Scheduled reminders for booking {}", event.getBookingRef());
        } catch (Exception e) {
            log.error("Failed to schedule reminders for {}: {}", event.getBookingRef(), e.getMessage());
        }
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
}
