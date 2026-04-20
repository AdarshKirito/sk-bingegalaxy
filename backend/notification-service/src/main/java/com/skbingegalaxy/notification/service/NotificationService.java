package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.dto.NotificationDto;
import com.skbingegalaxy.notification.model.DeliveryStatus;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.model.NotificationTemplate;
import com.skbingegalaxy.notification.model.WhatsAppTemplate;
import com.skbingegalaxy.notification.provider.PushProvider;
import com.skbingegalaxy.notification.provider.SmsProvider;
import com.skbingegalaxy.notification.provider.WhatsAppProvider;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import com.skbingegalaxy.notification.repository.WhatsAppTemplateRepository;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final NotificationPreferenceService preferenceService;
    private final TemplateService templateService;
    private final WhatsAppTemplateRepository whatsAppTemplateRepository;
    private final SmsProvider smsProvider;
    private final WhatsAppProvider whatsAppProvider;
    private final PushProvider pushProvider;

    @Value("${app.notification.from-email:noreply@skbingegalaxy.com}")
    private String fromEmail;

    @Value("${app.notification.max-retries:3}")
    private int maxRetries;

    @Value("${app.notification.unsubscribe-url:https://skbingegalaxy.com/account/notifications}")
    private String unsubscribeUrl;

    private static final int RETRY_BATCH_SIZE = 100;

    /** Exponential backoff intervals in minutes: 1m, 5m, 30m (capped). */
    private static final long[] BACKOFF_MINUTES = {1, 5, 30};

    /** Matches unreplaced {{variable}} placeholders left after substitution. */
    private static final Pattern UNREPLACED_VARS = Pattern.compile("\\{\\{[^}]+}}");

    public NotificationService(
            NotificationRepository notificationRepository,
            @Autowired(required = false) JavaMailSender mailSender,
            TemplateEngine templateEngine,
            NotificationPreferenceService preferenceService,
            TemplateService templateService,
            WhatsAppTemplateRepository whatsAppTemplateRepository,
            @Autowired(required = false) SmsProvider smsProvider,
            @Autowired(required = false) WhatsAppProvider whatsAppProvider,
            @Autowired(required = false) PushProvider pushProvider) {
        this.notificationRepository = notificationRepository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.preferenceService = preferenceService;
        this.templateService = templateService;
        this.whatsAppTemplateRepository = whatsAppTemplateRepository;
        this.smsProvider = smsProvider;
        this.whatsAppProvider = whatsAppProvider;
        this.pushProvider = pushProvider;
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

        // ── Check user preferences before persisting ──
        if (recipientEmail != null
                && preferenceService.isSuppressed(recipientEmail, type, channel.name())) {
            log.info("Notification suppressed by user preference: type={} channel={} email={}",
                    type, channel, recipientEmail);
            Notification suppressed = Notification.builder()
                    .type(type).channel(channel).recipientEmail(recipientEmail)
                    .recipientPhone(recipientPhone).recipientName(recipientName)
                    .subject(subject).body(body).bookingRef(bookingRef).metadata(metadata)
                    .sent(false).failureReason("Suppressed by user preference")
                    .retryCount(0).createdAt(LocalDateTime.now()).build();
            return toDto(notificationRepository.save(suppressed));
        }

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
            notification.setDeliveryStatus(DeliveryStatus.SENT);
        } else {
            notification.setDeliveryStatus(DeliveryStatus.PENDING);
            notification.setNextRetryAt(computeNextRetryAt(0));
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

    public List<NotificationDto> getByBookingRefAndEmail(String bookingRef, String email) {
        return notificationRepository.findByBookingRefAndRecipientEmailOrderByCreatedAtDesc(bookingRef, email)
            .stream().map(this::toDto).toList();
    }

    /**
     * Retry failed notifications. Cluster-safety is handled by ShedLock at
     * the scheduler layer — no in-process AtomicBoolean needed.
     */
    public void retryFailedNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<Notification> failed = notificationRepository
            .findRetryableNotifications(maxRetries, now,
                org.springframework.data.domain.PageRequest.of(0, RETRY_BATCH_SIZE));

        for (Notification n : failed) {
            // Skip notifications that were suppressed by preference
            if ("Suppressed by user preference".equals(n.getFailureReason())) continue;

            n.setRetryCount(n.getRetryCount() + 1);
            boolean success = dispatchNotification(n);
            n.setSent(success);
            if (success) {
                n.setSentAt(LocalDateTime.now());
                n.setDeliveryStatus(DeliveryStatus.SENT);
                n.setNextRetryAt(null);
            } else {
                n.setNextRetryAt(computeNextRetryAt(n.getRetryCount()));
            }
            notificationRepository.save(n);
        }
        log.info("Retried {} failed notifications", failed.size());
    }

    /**
     * Compute the next retry time using exponential backoff.
     * Intervals: 1 min → 5 min → 30 min (capped).
     */
    static LocalDateTime computeNextRetryAt(int retryCount) {
        int idx = Math.min(retryCount, BACKOFF_MINUTES.length - 1);
        return LocalDateTime.now().plusMinutes(BACKOFF_MINUTES[idx]);
    }

    private boolean dispatchNotification(Notification notification) {
        try {
            switch (notification.getChannel()) {
                case EMAIL -> {
                    if (notification.getRecipientEmail() == null || notification.getRecipientEmail().isBlank()) {
                        log.warn("Skipping EMAIL notification {} — no recipient email", notification.getId());
                        notification.setFailureReason("No recipient email address");
                        return false;
                    }
                    sendEmail(notification);
                }
                case SMS -> sendSms(notification);
                case WHATSAPP -> sendWhatsApp(notification);
                case PUSH -> sendPush(notification);
            }
            return notification.getFailureReason() == null;
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
        "PASSWORD_RESET", "password-reset",
        "BOOKING_REMINDER", "booking-reminder"
    );

    private void sendEmail(Notification notification) throws MessagingException {
        if (mailSender == null) {
            throw new IllegalStateException("Email delivery is not configured");
        }
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(notification.getRecipientEmail());
        helper.setSubject(notification.getSubject());

        // ── CAN-SPAM / List-Unsubscribe headers ──
        String personalUnsubUrl = unsubscribeUrl + "?email=" + URLEncoder.encode(notification.getRecipientEmail(), StandardCharsets.UTF_8);
        mimeMessage.addHeader("List-Unsubscribe", "<" + personalUnsubUrl + ">");
        mimeMessage.addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");

        String templateName = TEMPLATE_MAP.get(notification.getType());

        // Priority 1: Check for a DB-stored active template
        java.util.Optional<NotificationTemplate> dbTemplate = templateService
                .getActiveTemplate(notification.getType(), "EMAIL");
        if (dbTemplate.isPresent()) {
            NotificationTemplate tpl = dbTemplate.get();
            // Simple variable substitution: {{key}} → value
            String html = tpl.getContent();
            html = html.replace("{{recipientName}}", notification.getRecipientName() != null ? notification.getRecipientName() : "");
            html = html.replace("{{customerName}}", notification.getRecipientName() != null ? notification.getRecipientName() : "");
            html = html.replace("{{bookingRef}}", notification.getBookingRef() != null ? notification.getBookingRef() : "");
            html = html.replace("{{unsubscribeUrl}}", personalUnsubUrl);
            if (notification.getMetadata() != null) {
                for (Map.Entry<String, Object> entry : notification.getMetadata().entrySet()) {
                    String escaped = org.springframework.web.util.HtmlUtils.htmlEscape(String.valueOf(entry.getValue()));
                    html = html.replace("{{" + entry.getKey() + "}}", escaped);
                }
            }
            // Remove any unreplaced {{variable}} placeholders
            html = UNREPLACED_VARS.matcher(html).replaceAll("");
            helper.setText(html, true);
            if (tpl.getSubject() != null && !tpl.getSubject().isBlank()) {
                helper.setSubject(tpl.getSubject());
            }
        } else if (templateName != null) {
            // Priority 2: Fall back to Thymeleaf file-based template
            Context ctx = new Context();
            ctx.setVariable("recipientName", notification.getRecipientName());
            ctx.setVariable("customerName", notification.getRecipientName());
            ctx.setVariable("bookingRef", notification.getBookingRef());
            ctx.setVariable("unsubscribeUrl", personalUnsubUrl);
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
        if (notification.getRecipientPhone() == null || notification.getRecipientPhone().isBlank()) {
            log.warn("SMS notification {} skipped — no recipient phone number", notification.getId());
            notification.setFailureReason("No recipient phone number");
            return;
        }
        if (smsProvider == null) {
            log.warn("SMS notification {} skipped — no SMS provider configured", notification.getId());
            notification.setFailureReason("SMS provider not configured (set app.sms.provider)");
            return;
        }
        String sid = smsProvider.send(notification.getRecipientPhone(), notification.getBody());
        log.info("SMS dispatched via {} — SID/ID: {}", smsProvider.providerName(), sid);
    }

    private void sendWhatsApp(Notification notification) {
        if (notification.getRecipientPhone() == null || notification.getRecipientPhone().isBlank()) {
            log.warn("WhatsApp notification {} skipped — no recipient phone number", notification.getId());
            notification.setFailureReason("No recipient phone number");
            return;
        }
        if (whatsAppProvider == null) {
            log.warn("WhatsApp notification {} skipped — no WhatsApp provider configured", notification.getId());
            notification.setFailureReason("WhatsApp provider not configured (set app.whatsapp.provider)");
            return;
        }

        // Check for a pre-approved Content SID template
        java.util.Optional<WhatsAppTemplate> waTpl = whatsAppTemplateRepository
                .findByTemplateNameAndActiveTrue(notification.getType());
        String sid;
        if (waTpl.isPresent()) {
            Map<String, String> vars = new HashMap<>();
            if (notification.getRecipientName() != null) vars.put("1", notification.getRecipientName());
            if (notification.getBookingRef() != null) vars.put("2", notification.getBookingRef());
            sid = whatsAppProvider.sendWithContentSid(
                    notification.getRecipientPhone(), waTpl.get().getContentSid(), vars);
            log.info("WhatsApp (ContentSID) dispatched via {} — SID: {}", whatsAppProvider.providerName(), sid);
        } else {
            sid = whatsAppProvider.send(notification.getRecipientPhone(), notification.getBody());
            log.info("WhatsApp dispatched via {} — SID/ID: {}", whatsAppProvider.providerName(), sid);
        }
    }

    private void sendPush(Notification notification) {
        if (pushProvider == null) {
            log.warn("Push notification {} skipped — no push provider configured", notification.getId());
            notification.setFailureReason("Push provider not configured (set app.push.provider)");
            return;
        }
        // deviceToken is expected in metadata
        String deviceToken = notification.getMetadata() != null
                ? String.valueOf(notification.getMetadata().getOrDefault("deviceToken", ""))
                : "";
        if (deviceToken.isBlank()) {
            log.warn("Push notification {} skipped — no device token in metadata", notification.getId());
            notification.setFailureReason("No device token provided");
            return;
        }
        Map<String, String> data = new HashMap<>();
        data.put("type", notification.getType());
        if (notification.getBookingRef() != null) data.put("bookingRef", notification.getBookingRef());

        // Rich push fields from metadata
        String imageUrl = extractString(notification.getMetadata(), "imageUrl");
        String deepLinkUrl = extractString(notification.getMetadata(), "deepLinkUrl");
        Map<String, String> actionButtons = extractActionButtons(notification.getMetadata());

        String msgId = pushProvider.sendRich(deviceToken, notification.getSubject(), notification.getBody(),
                data, imageUrl, deepLinkUrl, actionButtons);
        log.info("Push dispatched via {} — messageId: {}", pushProvider.providerName(), msgId);
    }

    private String extractString(Map<String, Object> metadata, String key) {
        if (metadata == null) return null;
        Object val = metadata.get(key);
        return val != null ? val.toString() : null;
    }

    private Map<String, String> extractActionButtons(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object raw = metadata.get("actionButtons");
        if (raw instanceof Map<?, ?> map) {
            Map<String, String> result = new HashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result.isEmpty() ? null : result;
        }
        return null;
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
            .deliveryStatus(n.getDeliveryStatus())
            .deliveredAt(n.getDeliveredAt())
            .openedAt(n.getOpenedAt())
            .clickedAt(n.getClickedAt())
            .bouncedAt(n.getBouncedAt())
            .nextRetryAt(n.getNextRetryAt())
            .build();
    }
}
