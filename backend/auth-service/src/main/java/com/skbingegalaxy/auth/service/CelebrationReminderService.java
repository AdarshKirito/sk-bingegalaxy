package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CelebrationReminderService {

    private static final DateTimeFormatter MONTH_FORMATTER = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.FULL)
        .toFormatter(Locale.ENGLISH);

    private final UserRepository userRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.reminders.enabled:true}")
    private boolean remindersEnabled;

    @Value("${app.reminders.zone:Asia/Kolkata}")
    private String reminderZone;

    @Value("${app.admin.email:admin@skbingegalaxy.com}")
    private String supportEmail;

    @Scheduled(cron = "${app.reminders.cron:0 0 9 * * *}", zone = "${app.reminders.zone:Asia/Kolkata}")
    public void runDailyCelebrationReminders() {
        if (!remindersEnabled) {
            return;
        }
        processCelebrationReminders(LocalDate.now(ZoneId.of(reminderZone)));
    }

    @Transactional
    public int processCelebrationReminders(LocalDate today) {
        List<User> users = userRepository.findCustomersWithCelebrationReminders();
        int dispatched = 0;

        for (User user : users) {
            dispatched += maybeDispatchReminder(user, today, "Birthday", user.getBirthdayMonth(), user.getBirthdayDay(), user.getBirthdayReminderSentYear());
            dispatched += maybeDispatchReminder(user, today, "Anniversary", user.getAnniversaryMonth(), user.getAnniversaryDay(), user.getAnniversaryReminderSentYear());
        }

        if (dispatched > 0) {
            userRepository.saveAll(users);
            log.info("Dispatched {} celebration reminder notification(s)", dispatched);
        }

        return dispatched;
    }

    private int maybeDispatchReminder(User user, LocalDate today, String celebrationType, String month, Integer day, Integer sentYear) {
        if (month == null || day == null) {
            return 0;
        }

        LocalDate occurrence = resolveReminderOccurrence(today, month, day, user.getReminderLeadDays());
        if (!today.equals(occurrence.minusDays(defaultLeadDays(user.getReminderLeadDays()))) || (sentYear != null && sentYear.equals(occurrence.getYear()))) {
            return 0;
        }

        int dispatched = dispatchReminder(user, celebrationType, occurrence);
        if ("Birthday".equals(celebrationType)) {
            user.setBirthdayReminderSentYear(occurrence.getYear());
        } else {
            user.setAnniversaryReminderSentYear(occurrence.getYear());
        }
        return dispatched;
    }

    private LocalDate resolveReminderOccurrence(LocalDate today, String month, Integer day, Integer leadDays) {
        LocalDate occurrence = buildCelebrationDate(today.getYear(), month, day);
        if (today.isAfter(occurrence.minusDays(defaultLeadDays(leadDays)))) {
            occurrence = buildCelebrationDate(today.getYear() + 1, month, day);
        }
        return occurrence;
    }

    private int dispatchReminder(User user, String celebrationType, LocalDate celebrationDate) {
        String deliveryPreference = normalizeDeliveryPreference(user.getNotificationChannel());
        if ("CALLBACK".equals(deliveryPreference)) {
            publishCallbackRequest(user, celebrationType, celebrationDate);
            publishCustomerReminder(user, NotificationChannel.EMAIL, celebrationType, celebrationDate,
                "We have queued your callback reminder request and the team will reach out before the celebration.");
            return 2;
        }

        NotificationChannel channel = resolveNotificationChannel(deliveryPreference, user.getPhone());
        publishCustomerReminder(user, channel, celebrationType, celebrationDate, null);
        return 1;
    }

    private void publishCustomerReminder(User user, NotificationChannel channel, String celebrationType, LocalDate celebrationDate, String overrideBody) {
        NotificationEvent event = NotificationEvent.builder()
            .recipientEmail(user.getEmail())
            .recipientPhone(user.getPhone())
            .recipientName(user.getFirstName())
            .channel(channel)
            .type("CELEBRATION_REMINDER")
            .subject(celebrationType + " reminder from SK Binge Galaxy")
            .body(overrideBody != null ? overrideBody : buildCustomerReminderBody(user, celebrationType, celebrationDate, channel))
            .metadata(buildReminderMetadata(user, celebrationType, celebrationDate, channel.name()))
            .build();
        kafkaTemplate.send(KafkaTopics.NOTIFICATION_SEND, event);
    }

    private void publishCallbackRequest(User user, String celebrationType, LocalDate celebrationDate) {
        NotificationEvent event = NotificationEvent.builder()
            .recipientEmail(supportEmail)
            .recipientPhone(user.getPhone())
            .recipientName(user.getFirstName())
            .channel(NotificationChannel.EMAIL)
            .type("CELEBRATION_CALLBACK_REQUEST")
            .subject("Celebration callback requested for " + user.getFirstName())
            .body(buildCallbackSupportBody(user, celebrationType, celebrationDate))
            .metadata(buildReminderMetadata(user, celebrationType, celebrationDate, "CALLBACK"))
            .build();
        kafkaTemplate.send(KafkaTopics.NOTIFICATION_SEND, event);
    }

    private Map<String, Object> buildReminderMetadata(User user, String celebrationType, LocalDate celebrationDate, String channel) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", user.getFirstName());
        metadata.put("celebrationType", celebrationType);
        metadata.put("celebrationDate", celebrationDate.toString());
        metadata.put("preferredExperience", user.getPreferredExperience());
        metadata.put("vibePreference", user.getVibePreference());
        metadata.put("reminderLeadDays", defaultLeadDays(user.getReminderLeadDays()));
        metadata.put("notificationPreference", channel);
        return metadata;
    }

    private String buildCustomerReminderBody(User user, String celebrationType, LocalDate celebrationDate, NotificationChannel channel) {
        return String.format("""
            Hi %s,

            Your %s is coming up on %s.
            We are reaching out %d day(s) early so you have time to plan your celebration at SK Binge Galaxy.

            Preferred experience: %s
            Preferred vibe: %s
            Reminder channel used: %s

            Reply to this message or sign in to your account to start planning.

            SK Binge Galaxy Team""",
            user.getFirstName(),
            celebrationType.toLowerCase(Locale.ENGLISH),
            celebrationDate,
            defaultLeadDays(user.getReminderLeadDays()),
            fallbackText(user.getPreferredExperience(), "Not set"),
            fallbackText(user.getVibePreference(), "Not set"),
            channel.name());
    }

    private String buildCallbackSupportBody(User user, String celebrationType, LocalDate celebrationDate) {
        return String.format("""
            Celebration callback request

            Customer: %s %s
            Email: %s
            Phone: %s
            Celebration: %s
            Celebration date: %s
            Lead time: %d day(s)
            Preferred experience: %s
            Preferred vibe: %s

            Please contact this customer before the celebration window.""",
            user.getFirstName(),
            fallbackText(user.getLastName(), ""),
            user.getEmail(),
            fallbackText(user.getPhone(), "Not provided"),
            celebrationType,
            celebrationDate,
            defaultLeadDays(user.getReminderLeadDays()),
            fallbackText(user.getPreferredExperience(), "Not set"),
            fallbackText(user.getVibePreference(), "Not set"));
    }

    private NotificationChannel resolveNotificationChannel(String deliveryPreference, String phone) {
        return NotificationChannel.EMAIL;
    }

    private String normalizeDeliveryPreference(String notificationChannel) {
        if (notificationChannel == null || notificationChannel.isBlank()) {
            return "EMAIL";
        }
        String normalized = notificationChannel.trim().toUpperCase(Locale.ENGLISH);
        return "CALLBACK".equals(normalized) ? "CALLBACK" : "EMAIL";
    }

    private LocalDate buildCelebrationDate(int year, String month, Integer day) {
        Month parsedMonth = Month.from(MONTH_FORMATTER.parse(month));
        int safeDay = parsedMonth == Month.FEBRUARY && day == 29 && !java.time.Year.isLeap(year) ? 28 : day;
        return LocalDate.of(year, parsedMonth, safeDay);
    }

    private int defaultLeadDays(Integer leadDays) {
        return leadDays != null ? leadDays : 14;
    }

    private String fallbackText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}