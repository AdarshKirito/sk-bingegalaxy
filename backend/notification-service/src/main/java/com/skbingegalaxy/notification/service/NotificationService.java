package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.dto.NotificationDto;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.notification.from-email:noreply@skbingegalaxy.com}")
    private String fromEmail;

    @Value("${app.notification.max-retries:3}")
    private int maxRetries;

    public NotificationService(
            NotificationRepository notificationRepository,
            @Autowired(required = false) JavaMailSender mailSender,
            TemplateEngine templateEngine) {
        this.notificationRepository = notificationRepository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    public NotificationDto sendNotification(
            String type,
            NotificationChannel channel,
            String recipientEmail,
            String recipientPhone,
            String recipientName,
            String subject,
            String body,
            String bookingRef,
            Map<String, Object> metadata) {

        Notification notification = Notification.builder()
            .type(type)
            .channel(channel)
            .recipientEmail(recipientEmail)
            .recipientPhone(recipientPhone)
            .recipientName(recipientName)
            .subject(subject)
            .body(body)
            .bookingRef(bookingRef)
            .metadata(metadata)
            .sent(false)
            .retryCount(0)
            .createdAt(LocalDateTime.now())
            .build();

        notification = notificationRepository.save(notification);

        boolean success = dispatchNotification(notification);
        notification.setSent(success);
        if (success) {
            notification.setSentAt(LocalDateTime.now());
        }
        notification = notificationRepository.save(notification);

        return toDto(notification);
    }

    public List<NotificationDto> getByEmail(String email) {
        return notificationRepository.findByRecipientEmailOrderByCreatedAtDesc(email)
            .stream().map(this::toDto).toList();
    }

    public List<NotificationDto> getByBookingRef(String bookingRef) {
        return notificationRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef)
            .stream().map(this::toDto).toList();
    }

    public void retryFailedNotifications() {
        List<Notification> failed = notificationRepository
            .findBySentFalseAndRetryCountLessThan(maxRetries);

        for (Notification n : failed) {
            n.setRetryCount(n.getRetryCount() + 1);
            boolean success = dispatchNotification(n);
            n.setSent(success);
            if (success) {
                n.setSentAt(LocalDateTime.now());
            }
            notificationRepository.save(n);
        }
        log.info("Retried {} failed notifications", failed.size());
    }

    private boolean dispatchNotification(Notification notification) {
        try {
            switch (notification.getChannel()) {
                case EMAIL -> sendEmail(notification);
                case SMS -> sendSms(notification);
                case WHATSAPP -> sendWhatsApp(notification);
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to send {} notification to {}: {}",
                notification.getChannel(), notification.getRecipientEmail(), e.getMessage());
            notification.setFailureReason(e.getMessage());
            return false;
        }
    }

    private static final Map<String, String> TEMPLATE_MAP = Map.of(
        "BOOKING_CREATED", "booking-created",
        "BOOKING_CANCELLED", "booking-cancelled",
        "PAYMENT_SUCCESS", "payment-success",
        "PAYMENT_FAILED", "payment-failed",
        "USER_REGISTERED", "welcome",
        "PASSWORD_RESET", "password-reset"
    );

    private void sendEmail(Notification notification) throws MessagingException {
        if (mailSender == null) {
            log.warn("JavaMailSender not configured — email to {} logged only", notification.getRecipientEmail());
            return;
        }
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(notification.getRecipientEmail());
        helper.setSubject(notification.getSubject());

        String templateName = TEMPLATE_MAP.get(notification.getType());
        if (templateName != null) {
            Context ctx = new Context();
            ctx.setVariable("recipientName", notification.getRecipientName());
            ctx.setVariable("customerName", notification.getRecipientName());
            ctx.setVariable("bookingRef", notification.getBookingRef());
            if (notification.getMetadata() != null) {
                notification.getMetadata().forEach(ctx::setVariable);
            }
            String html = templateEngine.process(templateName, ctx);
            helper.setText(html, true);
        } else {
            helper.setText(notification.getBody(), false);
        }

        mailSender.send(mimeMessage);
        log.info("Email sent to: {}, subject: {}", notification.getRecipientEmail(), notification.getSubject());
    }

    private void sendSms(Notification notification) {
        // Mock SMS — replace with Twilio / MSG91 / AWS SNS integration
        log.info("[SMS Mock] To: {}, Message: {}", notification.getRecipientPhone(), notification.getBody());
    }

    private void sendWhatsApp(Notification notification) {
        // Mock WhatsApp — replace with Twilio WhatsApp / WhatsApp Business API integration
        log.info("[WhatsApp Mock] To: {}, Message: {}", notification.getRecipientPhone(), notification.getBody());
    }

    private NotificationDto toDto(Notification n) {
        return NotificationDto.builder()
            .id(n.getId())
            .recipientEmail(n.getRecipientEmail())
            .recipientPhone(n.getRecipientPhone())
            .recipientName(n.getRecipientName())
            .type(n.getType())
            .channel(n.getChannel())
            .subject(n.getSubject())
            .body(n.getBody())
            .metadata(n.getMetadata())
            .bookingRef(n.getBookingRef())
            .sent(n.isSent())
            .failureReason(n.getFailureReason())
            .retryCount(n.getRetryCount())
            .sentAt(n.getSentAt())
            .createdAt(n.getCreatedAt())
            .build();
    }
}
