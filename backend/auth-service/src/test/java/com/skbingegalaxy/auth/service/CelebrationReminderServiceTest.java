package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CelebrationReminderServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    private CelebrationReminderService celebrationReminderService;

    @BeforeEach
    void setUp() {
        celebrationReminderService = new CelebrationReminderService(userRepository, kafkaTemplate);
        ReflectionTestUtils.setField(celebrationReminderService, "remindersEnabled", true);
        ReflectionTestUtils.setField(celebrationReminderService, "reminderZone", "Asia/Kolkata");
        ReflectionTestUtils.setField(celebrationReminderService, "supportEmail", "support@example.com");
    }

    @Test
    void processCelebrationReminders_sendsBirthdayReminder() {
        User user = User.builder()
            .id(1L)
            .firstName("Ava")
            .email("ava@example.com")
            .phone("9876543210")
            .role(UserRole.CUSTOMER)
            .active(true)
            .birthdayMonth("July")
            .birthdayDay(14)
            .reminderLeadDays(7)
            .notificationChannel("EMAIL")
            .build();

        when(userRepository.findCustomersWithCelebrationReminders()).thenReturn(List.of(user));

        int sent = celebrationReminderService.processCelebrationReminders(LocalDate.of(2026, 7, 7));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("notification.send"), eventCaptor.capture());
        verify(userRepository).saveAll(any());

        NotificationEvent event = (NotificationEvent) eventCaptor.getValue();
        assertThat(sent).isEqualTo(1);
        assertThat(event.getRecipientEmail()).isEqualTo("ava@example.com");
        assertThat(event.getType()).isEqualTo("CELEBRATION_REMINDER");
        assertThat(event.getSubject()).contains("Birthday reminder");
        assertThat(user.getBirthdayReminderSentYear()).isEqualTo(2026);
    }

    @Test
    void processCelebrationReminders_callbackPreference_notifiesSupportAndCustomer() {
        User user = User.builder()
            .id(2L)
            .firstName("Noah")
            .lastName("Ray")
            .email("noah@example.com")
            .phone("9999999999")
            .role(UserRole.CUSTOMER)
            .active(true)
            .anniversaryMonth("May")
            .anniversaryDay(20)
            .reminderLeadDays(5)
            .notificationChannel("CALLBACK")
            .preferredExperience("Anniversary dinner")
            .vibePreference("Quiet")
            .build();

        when(userRepository.findCustomersWithCelebrationReminders()).thenReturn(List.of(user));

        int sent = celebrationReminderService.processCelebrationReminders(LocalDate.of(2026, 5, 15));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(eq("notification.send"), eventCaptor.capture());
        verify(userRepository).saveAll(any());

        List<Object> events = eventCaptor.getAllValues();
        NotificationEvent supportEvent = (NotificationEvent) events.get(0);
        NotificationEvent customerEvent = (NotificationEvent) events.get(1);
        assertThat(sent).isEqualTo(2);
        assertThat(supportEvent.getRecipientEmail()).isEqualTo("support@example.com");
        assertThat(supportEvent.getType()).isEqualTo("CELEBRATION_CALLBACK_REQUEST");
        assertThat(customerEvent.getRecipientEmail()).isEqualTo("noah@example.com");
        assertThat(customerEvent.getType()).isEqualTo("CELEBRATION_REMINDER");
        assertThat(user.getAnniversaryReminderSentYear()).isEqualTo(2026);
    }
}