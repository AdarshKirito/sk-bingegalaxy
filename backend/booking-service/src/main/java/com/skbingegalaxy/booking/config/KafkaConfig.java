package com.skbingegalaxy.booking.config;

import lombok.extern.slf4j.Slf4j;
import com.skbingegalaxy.common.constants.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
public class KafkaConfig {

    /**
     * Wire a {@link DefaultErrorHandler} that retries 3 times (2 s apart) then routes the
     * poison message to the corresponding {@code <topic>-dlt} topic. This prevents a bad
     * message from blocking the partition indefinitely while still giving transient failures
     * (DB blip, downstream timeout) a chance to recover.
     *
     * Non-retryable exceptions (deserialization failures) skip retries entirely and go
     * straight to the DLT — retrying a corrupt payload never helps.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> {
                log.error("kafka.dlt.publish topic={} partition={} offset={} ex={}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage());
                return new TopicPartition(record.topic() + "-dlt", record.partition());
            });
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(2_000L, 3L));
        handler.addNotRetryableExceptions(
            org.springframework.kafka.support.serializer.DeserializationException.class,
            org.apache.kafka.common.errors.SerializationException.class
        );
        return handler;
    }

    /**
     * Override the auto-configured factory to attach our error handler while keeping all
     * application.yml Kafka listener settings (ack-mode, concurrency, isolation, etc.).
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ObjectProvider<ConsumerFactory<Object, Object>> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory.getIfAvailable());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    @Bean
    public NewTopic bookingCreatedTopic() {
        return TopicBuilder.name("booking.created").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name("booking.confirmed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingCancelledTopic() {
        return TopicBuilder.name("booking.cancelled").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingCheckedInTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_CHECKED_IN).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingCompletedTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic waitlistPromotedTopic() {
        return TopicBuilder.name(KafkaTopics.WAITLIST_PROMOTED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingRescheduledTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_RESCHEDULED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingTransferredTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_TRANSFERRED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentSuccessDltTopic() {
        return deadLetterTopic(KafkaTopics.PAYMENT_SUCCESS);
    }

    @Bean
    public NewTopic paymentFailedDltTopic() {
        return deadLetterTopic(KafkaTopics.PAYMENT_FAILED);
    }

    @Bean
    public NewTopic paymentRefundedDltTopic() {
        return deadLetterTopic(KafkaTopics.PAYMENT_REFUNDED);
    }

    @Bean
    public NewTopic bookingCancelledDltTopic() {
        return deadLetterTopic(KafkaTopics.BOOKING_CANCELLED);
    }

    // DLTs for the new rescheduled/transferred topics so the error handler
    // has a routing target when a downstream consumer fails to process them.
    @Bean
    public NewTopic bookingRescheduledDltTopic() {
        return deadLetterTopic(KafkaTopics.BOOKING_RESCHEDULED);
    }

    @Bean
    public NewTopic bookingTransferredDltTopic() {
        return deadLetterTopic(KafkaTopics.BOOKING_TRANSFERRED);
    }

    private NewTopic deadLetterTopic(String topic) {
        return TopicBuilder.name(topic + "-dlt")
            .partitions(3)
            .replicas(1)
            .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
            .build();
    }
}
