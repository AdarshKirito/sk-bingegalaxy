package com.skbingegalaxy.booking.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.config.AdminEventBus;
import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.BookingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the booking-service outbox publisher. Mirrors the payment-service
 * test suite so that the two publishers stay behaviourally aligned.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private AdminEventBus adminEventBus;

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        // Construct manually because ObjectMapper is real, not a mock.
        outboxPublisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate,
            objectMapper, adminEventBus);
    }

    private static CompletableFuture<SendResult<String, Object>> failedFuture(Throwable t) {
        CompletableFuture<SendResult<String, Object>> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    private OutboxEvent bookingEvent(long id, String ref) throws Exception {
        BookingEvent be = BookingEvent.builder()
            .bookingRef(ref)
            .bingeId(1L)
            .customerName("John")
            .bookingDate(LocalDate.of(2026, 4, 7))
            .startTime(LocalTime.of(18, 0))
            .build();
        return OutboxEvent.builder()
            .id(id)
            .topic(KafkaTopics.BOOKING_CREATED)
            .aggregateKey(ref)
            .payload(objectMapper.writeValueAsString(be))
            .sent(false)
            .build();
    }

    @Test
    void publishesAllSuccessful() throws Exception {
        OutboxEvent e1 = bookingEvent(1L, "SKBG25000001");
        OutboxEvent e2 = bookingEvent(2L, "SKBG25000002");
        when(outboxEventRepository.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
            .thenReturn(List.of(e1, e2));
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        assertThat(e1.isSent()).isTrue();
        assertThat(e2.isSent()).isTrue();
        assertThat(e1.getSentAt()).isNotNull();
        verify(outboxEventRepository).save(e1);
        verify(outboxEventRepository).save(e2);
    }

    @Test
    void failingEventDoesNotBlockOthers() throws Exception {
        OutboxEvent poison = bookingEvent(1L, "SKBG25POISON");
        OutboxEvent good = bookingEvent(2L, "SKBG25000002");
        when(outboxEventRepository.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
            .thenReturn(List.of(poison, good));
        // First send fails, second succeeds.
        when(kafkaTemplate.send(eq(KafkaTopics.BOOKING_CREATED), eq("SKBG25POISON"), any()))
            .thenReturn(failedFuture(new RuntimeException("broker down")));
        when(kafkaTemplate.send(eq(KafkaTopics.BOOKING_CREATED), eq("SKBG25000002"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        assertThat(poison.isSent()).isFalse();
        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getLastError()).contains("broker down");
        assertThat(poison.isFailedPermanent()).isFalse();
        // The good event was NOT head-of-line blocked.
        assertThat(good.isSent()).isTrue();
    }

    @Test
    void marksPoisonedAfterMaxAttempts() throws Exception {
        OutboxEvent poison = bookingEvent(1L, "SKBG25POISON");
        poison.setAttempts(9); // One more failure will hit the max.
        when(outboxEventRepository.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
            .thenReturn(List.of(poison));
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
            .thenReturn(failedFuture(new RuntimeException("still broken")));

        outboxPublisher.publishPendingEvents();

        assertThat(poison.isFailedPermanent()).isTrue();
        assertThat(poison.getAttempts()).isEqualTo(10);
    }

    @Test
    void truncatesLongErrors() throws Exception {
        OutboxEvent event = bookingEvent(1L, "SKBG25000003");
        when(outboxEventRepository.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
            .thenReturn(List.of(event));
        String hugeMessage = "x".repeat(5000);
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
            .thenReturn(failedFuture(new RuntimeException(hugeMessage)));

        outboxPublisher.publishPendingEvents();

        assertThat(event.getLastError()).hasSize(1000);
    }

    @Test
    void emptyBatchIsNoOp() {
        when(outboxEventRepository.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
            .thenReturn(List.of());

        outboxPublisher.publishPendingEvents();

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publishesSseEventAfterSuccessfulKafkaSend() throws Exception {
        OutboxEvent e = bookingEvent(1L, "SKBG25000001");
        when(outboxEventRepository.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc())
            .thenReturn(List.of(e));
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        // bingeId=1 is set on the event — AdminEventBus should receive it.
        verify(adminEventBus, atLeastOnce()).publish(eq(1L), eq("booking"), any());
    }
}
