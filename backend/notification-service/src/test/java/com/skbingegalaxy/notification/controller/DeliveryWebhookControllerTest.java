package com.skbingegalaxy.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.notification.model.DeliveryStatus;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryWebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";

    @Mock private NotificationRepository notificationRepository;
    private DeliveryWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new DeliveryWebhookController(notificationRepository, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
    }

    /** Lowercase hex HMAC-SHA256 of the body — what a correctly configured provider sends. */
    private static String sign(String body) {
        return sign(body, SECRET);
    }

    private static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String eventJson(String notificationId, String event) {
        return "{\"notificationId\":\"" + notificationId + "\",\"event\":\"" + event + "\"}";
    }

    // ── Signature verification ───────────────────────────────────────────

    @Test
    @DisplayName("Rejects request when signature header is missing")
    void handleMissingSignature_returns401() {
        String body = eventJson("n1", "delivered");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(null, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(notificationRepository);
    }

    @Test
    @DisplayName("Rejects request when signature is computed with the wrong secret")
    void handleWrongSecret_returns401() {
        String body = eventJson("n1", "delivered");

        ResponseEntity<Void> response =
                controller.handleDeliveryEvent(sign(body, "attacker-secret"), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(notificationRepository);
    }

    @Test
    @DisplayName("Rejects request when the signed body was tampered with")
    void handleTamperedBody_returns401() {
        String original = eventJson("n1", "delivered");
        String tampered = eventJson("n1", "bounced");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(original), tampered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(notificationRepository);
    }

    @Test
    @DisplayName("Fail-closed: rejects every request when no secret is configured")
    void handleNoSecretConfigured_returns401() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");
        String body = eventJson("n1", "delivered");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(notificationRepository);
    }

    @Test
    @DisplayName("Accepts an uppercase hex signature (case-insensitive compare)")
    void handleUppercaseSignature_returns200() {
        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.SENT).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String body = eventJson("n1", "delivered");

        ResponseEntity<Void> response =
                controller.handleDeliveryEvent(sign(body).toUpperCase(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Payload validation ───────────────────────────────────────────────

    @Test
    @DisplayName("Rejects an unparseable body with 400")
    void handleUnparseableBody_returnsBadRequest() {
        String body = "this is not json";

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Rejects a body missing notificationId with 400")
    void handleMissingNotificationId_returnsBadRequest() {
        String body = "{\"event\":\"delivered\"}";

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Rejects unknown event types")
    void handleUnknownEvent_returnsBadRequest() {
        String body = eventJson("n1", "unknown_event");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Returns 404 for unknown notification")
    void handleUnknownNotification_returns404() {
        when(notificationRepository.findById("unknown")).thenReturn(Optional.empty());

        String body = eventJson("unknown", "delivered");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Status transitions ───────────────────────────────────────────────

    @Test
    @DisplayName("Processes delivered event and updates notification")
    void handleDelivered_updatesStatus() {
        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.SENT).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String body = eventJson("n1", "delivered");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notification.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(notification.getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("Idempotent: skips if notification is already in the same state")
    void handleIdempotent_skipsIfAlreadyDelivered() {
        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.DELIVERED).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));

        String body = eventJson("n1", "delivered");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Should not save since status was already DELIVERED
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Out-of-order 'delivered' does NOT downgrade a CLICKED notification")
    void lateDelivered_doesNotDowngradeClicked() {
        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.CLICKED).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String body = eventJson("n1", "delivered");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Status must stay CLICKED (engagement never regresses); deliveredAt may still be recorded.
        assertThat(notification.getDeliveryStatus()).isEqualTo(DeliveryStatus.CLICKED);
    }

    @Test
    @DisplayName("A late positive event does NOT clear a terminal BOUNCED state")
    void latePositiveEvent_doesNotClearBounced() {
        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.BOUNCED).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String body = eventJson("n1", "opened");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notification.getDeliveryStatus()).isEqualTo(DeliveryStatus.BOUNCED);
    }

    @Test
    @DisplayName("Forward engagement (DELIVERED → OPENED) still advances")
    void forwardEngagement_advances() {
        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.DELIVERED).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String body = eventJson("n1", "opened");

        controller.handleDeliveryEvent(sign(body), body);

        assertThat(notification.getDeliveryStatus()).isEqualTo(DeliveryStatus.OPENED);
        assertThat(notification.getOpenedAt()).isNotNull();
    }

    @Test
    @DisplayName("Processes bounced event correctly")
    void handleBounced_updatesStatus() {
        Notification notification = Notification.builder()
                .id("n1").deliveryStatus(DeliveryStatus.SENT).build();
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String body = eventJson("n1", "bounced");

        ResponseEntity<Void> response = controller.handleDeliveryEvent(sign(body), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notification.getDeliveryStatus()).isEqualTo(DeliveryStatus.BOUNCED);
        assertThat(notification.getBouncedAt()).isNotNull();
    }
}
