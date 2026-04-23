package com.skbingegalaxy.payment.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.common.event.PaymentEvent;
import com.skbingegalaxy.payment.entity.OutboxEvent;
import com.skbingegalaxy.payment.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    private OutboxPublisher publisher;

    private ObjectMapper realMapper;

    @BeforeEach
    void setUp() {
        realMapper = new ObjectMapper().findAndRegisterModules();
        // Construct with the real ObjectMapper so JSON round-trips work; Mockito would inject null.
        publisher = new OutboxPublisher(outboxRepo, kafkaTemplate, realMapper);
    }

    private OutboxEvent event(long id, String topic) throws Exception {
        PaymentEvent payload = PaymentEvent.builder()
                .bookingRef("SKBG-" + id)
                .transactionId("T" + id)
                .amount(BigDecimal.ONE)
                .build();
        OutboxEvent e = OutboxEvent.builder()
                .topic(topic)
                .aggregateKey("SKBG-" + id)
                .payload(realMapper.writeValueAsString(payload))
                .sent(false)
                .attempts(0)
                .failedPermanent(false)
                .createdAt(LocalDateTime.now())
                .build();
        e.setId(id);
        return e;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, Object>> successFuture() {
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, Object>> failedFuture(String msg) {
        CompletableFuture<SendResult<String, Object>> f = new CompletableFuture<>();
        // kafkaTemplate.send(...).get() will throw ExecutionException(cause=RuntimeException(msg)).
        // OutboxPublisher catches Exception and reads getMessage(), which yields a message
        // containing the original 'msg' — assertions test for a substring match.
        f.completeExceptionally(new RuntimeException(msg));
        return f;
    }

    @Test
    @DisplayName("Publishes all successful events and marks them sent")
    void publishesAllSuccessful() throws Exception {
        OutboxEvent e1 = event(1, "topicA");
        OutboxEvent e2 = event(2, "topicA");
        when(outboxRepo.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(e1, e2));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(successFuture());

        publisher.publishPendingEvents();

        assertThat(e1.isSent()).isTrue();
        assertThat(e2.isSent()).isTrue();
        verify(outboxRepo, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("A failing event does NOT block subsequent events (no head-of-line blocking)")
    void failingEventDoesNotBlockOthers() throws Exception {
        OutboxEvent e1 = event(1, "topicA");
        OutboxEvent e2 = event(2, "topicA");   // will fail
        OutboxEvent e3 = event(3, "topicA");
        when(outboxRepo.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(e1, e2, e3));
        when(kafkaTemplate.send(eq("topicA"), eq("SKBG-1"), any())).thenReturn(successFuture());
        when(kafkaTemplate.send(eq("topicA"), eq("SKBG-2"), any())).thenReturn(failedFuture("kafka down"));
        when(kafkaTemplate.send(eq("topicA"), eq("SKBG-3"), any())).thenReturn(successFuture());

        publisher.publishPendingEvents();

        // e1 and e3 must succeed even though e2 failed
        assertThat(e1.isSent()).isTrue();
        assertThat(e3.isSent()).isTrue();
        // e2 is NOT sent but should have its attempts incremented
        assertThat(e2.isSent()).isFalse();
        assertThat(e2.getAttempts()).isEqualTo(1);
        assertThat(e2.isFailedPermanent()).isFalse();
        assertThat(e2.getLastError()).contains("kafka down");
    }

    @Test
    @DisplayName("Event is marked failed_permanent after MAX_ATTEMPTS failures")
    void marksPoisonedAfterMaxAttempts() throws Exception {
        OutboxEvent e1 = event(1, "topicA");
        e1.setAttempts(OutboxPublisher.MAX_ATTEMPTS - 1); // one more failure makes it poisoned
        when(outboxRepo.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(e1));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture("broker down"));

        publisher.publishPendingEvents();

        assertThat(e1.getAttempts()).isEqualTo(OutboxPublisher.MAX_ATTEMPTS);
        assertThat(e1.isFailedPermanent()).isTrue();
        assertThat(e1.isSent()).isFalse();
    }

    @Test
    @DisplayName("Long error messages are truncated to 1000 chars so last_error column is not overflowed")
    void truncatesLongErrors() throws Exception {
        OutboxEvent e1 = event(1, "topicA");
        String bigMessage = "x".repeat(5_000);
        when(outboxRepo.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(e1));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture(bigMessage));

        publisher.publishPendingEvents();

        assertThat(e1.getLastError()).isNotNull();
        assertThat(e1.getLastError().length()).isLessThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("Empty pending list does not call Kafka or save anything")
    void emptyBatchIsNoOp() {
        when(outboxRepo.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
                .thenReturn(List.of());

        publisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(outboxRepo, never()).save(any());
    }
}
