package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.common.constants.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;

// The DLT consumer error handler (`kafkaErrorHandler`) is defined once in
// common-lib's KafkaDlqErrorHandlerConfig and injected into the factory below.
// Do NOT redefine it here — per-service copies collide on the bean name and
// crash startup with BeanDefinitionOverrideException.
@Configuration
public class KafkaConfig {

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

    // V56/V57 room-lifecycle topics. These are published via the transactional outbox
    // (publishRoomLifecycle / publishBlockLifecycle). The broker runs with
    // auto-create disabled, so they MUST be declared here or the OutboxPublisher relay
    // fails with UNKNOWN_TOPIC_OR_PARTITION and the outbox rows pile up unpublished.
    @Bean
    public NewTopic roomApprovedTopic() {
        return TopicBuilder.name(KafkaTopics.ROOM_APPROVED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic roomRejectedTopic() {
        return TopicBuilder.name(KafkaTopics.ROOM_REJECTED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic roomBlockedTopic() {
        return TopicBuilder.name(KafkaTopics.ROOM_BLOCKED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic roomUnblockedTopic() {
        return TopicBuilder.name(KafkaTopics.ROOM_UNBLOCKED).partitions(3).replicas(1).build();
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
