package com.skbingegalaxy.booking.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.config.AdminEventBus;
import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.BookingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private AdminEventBus adminEventBus;

    @InjectMocks private OutboxPublisher outboxPublisher;

    @Test
    void publishPendingEvents_marksEventSentOnlyAfterBrokerAcknowledges() throws Exception {
        OutboxEvent event = OutboxEvent.builder()
            .id(1L)
            .topic(KafkaTopics.BOOKING_CREATED)
            .aggregateKey("SKBG25123456")
            .payload("payload")
            .sent(false)
            .build();
        BookingEvent bookingEvent = BookingEvent.builder()
            .bookingRef("SKBG25123456")
            .customerName("John")
            .bookingDate(LocalDate.of(2026, 4, 7))
            .startTime(LocalTime.of(18, 0))
            .build();

        when(outboxEventRepository.findTop100BySentFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(objectMapper.readValue("payload", BookingEvent.class)).thenReturn(bookingEvent);
        when(kafkaTemplate.send(eq(KafkaTopics.BOOKING_CREATED), eq("SKBG25123456"), eq(bookingEvent)))
            .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        assertThat(event.isSent()).isTrue();
        assertThat(event.getSentAt()).isNotNull();
        verify(outboxEventRepository).save(event);
    }

    @Test
    void publishPendingEvents_keepsEventPendingWhenBrokerSendFails() throws Exception {
        OutboxEvent event = OutboxEvent.builder()
            .id(2L)
            .topic(KafkaTopics.BOOKING_CANCELLED)
            .aggregateKey("SKBG25123457")
            .payload("payload")
            .sent(false)
            .build();
        BookingEvent bookingEvent = BookingEvent.builder()
            .bookingRef("SKBG25123457")
            .build();
        CompletableFuture<org.springframework.kafka.support.SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("kafka unavailable"));

        when(outboxEventRepository.findTop100BySentFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(objectMapper.readValue("payload", BookingEvent.class)).thenReturn(bookingEvent);
        when(kafkaTemplate.send(eq(KafkaTopics.BOOKING_CANCELLED), eq("SKBG25123457"), eq(bookingEvent)))
            .thenReturn(failedFuture);

        outboxPublisher.publishPendingEvents();

        assertThat(event.isSent()).isFalse();
        assertThat(event.getSentAt()).isNull();
        verify(outboxEventRepository, never()).save(any());
    }
}