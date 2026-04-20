package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.notification.model.DeliveryStatus;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.skbingegalaxy.notification.dto.DeliveryEventDto;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryWebhookControllerTest {

    @Mock private NotificationRepository notificationRepository;
    @InjectMocks private DeliveryWebhookController controller;

    @Test
    @DisplayName("Processes delivered event and updates notification")
    void handleDelivered_updatesStatus() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.SENT).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeliveryEventDto dto = DeliveryEventDto.builder()
                .notificationId("n1").event("delivered").build();

        ResponseEntity<Void> response = controller.handleDeliveryEvent(null, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notification.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(notification.getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("Returns 404 for unknown notification")
    void handleUnknownNotification_returns404() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        when(notificationRepository.findById("unknown")).thenReturn(Optional.empty());

        DeliveryEventDto dto = DeliveryEventDto.builder()
                .notificationId("unknown").event("delivered").build();

        ResponseEntity<Void> response = controller.handleDeliveryEvent(null, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Rejects unknown event types")
    void handleUnknownEvent_returnsBadRequest() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        DeliveryEventDto dto = DeliveryEventDto.builder()
                .notificationId("n1").event("unknown_event").build();

        ResponseEntity<Void> response = controller.handleDeliveryEvent(null, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Rejects request when signature is missing but secret is configured")
    void handleMissingSignature_returns401() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "my-secret");

        DeliveryEventDto dto = DeliveryEventDto.builder()
                .notificationId("n1").event("delivered").build();

        ResponseEntity<Void> response = controller.handleDeliveryEvent(null, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Rejects request when signature is incorrect")
    void handleWrongSignature_returns401() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "my-secret");

        DeliveryEventDto dto = DeliveryEventDto.builder()
                .notificationId("n1").event("delivered").build();

        ResponseEntity<Void> response = controller.handleDeliveryEvent("wrong-secret", dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Accepts request when signature matches")
    void handleCorrectSignature_returns200() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "my-secret");

        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.SENT).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeliveryEventDto dto = DeliveryEventDto.builder()
                .notificationId("n1").event("delivered").build();

        ResponseEntity<Void> response = controller.handleDeliveryEvent("my-secret", dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Idempotent: skips if notification is already in the same state")
    void handleIdempotent_skipsIfAlreadyDelivered() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.DELIVERED).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));

        DeliveryEventDto dto = DeliveryEventDto.builder()
                .notificationId("n1").event("delivered").build();

        ResponseEntity<Void> response = controller.handleDeliveryEvent(null, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Should not save since status was already DELIVERED
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Processes bounced event correctly")
    void handleBounced_updatesStatus() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.SENT).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeliveryEventDto dto = DeliveryEventDto.builder()
                .notificationId("n1").event("bounced").build();

        ResponseEntity<Void> response = controller.handleDeliveryEvent(null, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notification.getDeliveryStatus()).isEqualTo(DeliveryStatus.BOUNCED);
        assertThat(notification.getBouncedAt()).isNotNull();
    }
}
