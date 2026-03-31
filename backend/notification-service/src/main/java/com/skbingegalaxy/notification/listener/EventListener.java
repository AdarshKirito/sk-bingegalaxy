package com.skbingegalaxy.notification.listener;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.BookingEvent;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.event.PaymentEvent;
import com.skbingegalaxy.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventListener {

    private final NotificationService notificationService;

    @KafkaListener(topics = KafkaTopics.BOOKING_CREATED, groupId = "notification-service")
    public void handleBookingCreated(BookingEvent event) {
        log.info("Booking created event: {}", event.getBookingRef());
        Map<String, Object> meta = new HashMap<>();
        meta.put("eventType", event.getEventTypeName());
        meta.put("bookingDate", event.getBookingDate().toString());
        meta.put("startTime", event.getStartTime().toString());
        meta.put("durationHours", event.getDurationHours());
        meta.put("totalAmount", event.getTotalAmount().toPlainString());
        notificationService.sendNotification(
            "BOOKING_CREATED",
            NotificationChannel.EMAIL,
            event.getCustomerEmail(),
            event.getCustomerPhone(),
            event.getCustomerName(),
            "Booking Confirmed - " + event.getBookingRef(),
            buildBookingCreatedBody(event),
            event.getBookingRef(),
            meta
        );
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = "notification-service")
    public void handleBookingCancelled(BookingEvent event) {
        log.info("Booking cancelled event: {}", event.getBookingRef());
        notificationService.sendNotification(
            "BOOKING_CANCELLED",
            NotificationChannel.EMAIL,
            event.getCustomerEmail(),
            event.getCustomerPhone(),
            event.getCustomerName(),
            "Booking Cancelled - " + event.getBookingRef(),
            buildBookingCancelledBody(event),
            event.getBookingRef(),
            Map.of("eventType", event.getEventTypeName())
        );
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCESS, groupId = "notification-service")
    public void handlePaymentSuccess(PaymentEvent event) {
        log.info("Payment success event: {}", event.getBookingRef());
        Map<String, Object> meta = new HashMap<>();
        meta.put("transactionId", event.getTransactionId());
        meta.put("amount", event.getAmount().toPlainString());
        meta.put("paymentMethod", event.getPaymentMethod());
        notificationService.sendNotification(
            "PAYMENT_SUCCESS",
            NotificationChannel.EMAIL,
            null, null, null,
            "Payment Successful - " + event.getBookingRef(),
            buildPaymentSuccessBody(event),
            event.getBookingRef(),
            meta
        );
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "notification-service")
    public void handlePaymentFailed(PaymentEvent event) {
        log.info("Payment failed event: {}", event.getBookingRef());
        Map<String, Object> meta = new HashMap<>();
        meta.put("transactionId", event.getTransactionId());
        notificationService.sendNotification(
            "PAYMENT_FAILED",
            NotificationChannel.EMAIL,
            null, null, null,
            "Payment Failed - " + event.getBookingRef(),
            buildPaymentFailedBody(event),
            event.getBookingRef(),
            meta
        );
    }

    @KafkaListener(topics = KafkaTopics.NOTIFICATION_SEND, groupId = "notification-service")
    public void handleDirectNotification(NotificationEvent event) {
        log.info("Direct notification event: type={}, to={}", event.getType(), event.getRecipientEmail());
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
    }

    @KafkaListener(topics = KafkaTopics.USER_REGISTERED, groupId = "notification-service")
    public void handleUserRegistered(NotificationEvent event) {
        log.info("User registered event: {}", event.getRecipientEmail());
        notificationService.sendNotification(
            "USER_REGISTERED",
            NotificationChannel.EMAIL,
            event.getRecipientEmail(),
            event.getRecipientPhone(),
            event.getRecipientName(),
            "Welcome to SK Binge Galaxy!",
            buildWelcomeBody(event),
            null,
            null
        );
    }

    @KafkaListener(topics = KafkaTopics.PASSWORD_RESET, groupId = "notification-service")
    public void handlePasswordReset(NotificationEvent event) {
        log.info("Password reset event: {}", event.getRecipientEmail());
        Map<String, Object> meta = event.getMetadata() != null ? new HashMap<>(event.getMetadata()) : new HashMap<>();
        meta.put("name", event.getRecipientName());
        notificationService.sendNotification(
            "PASSWORD_RESET",
            NotificationChannel.EMAIL,
            event.getRecipientEmail(),
            null,
            event.getRecipientName(),
            "Password Reset Request - SK Binge Galaxy",
            event.getBody(),
            null,
            meta
        );
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
}
