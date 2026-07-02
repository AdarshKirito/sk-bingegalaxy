package com.skbingegalaxy.payment.config;

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
     * Override the auto-configured factory to attach our error handler while keeping
     * all application.yml Kafka listener settings (ack-mode, concurrency, isolation, etc.).
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

    // ── Topics produced by payment-service (via OutboxPublisher) ──────────────

    @Bean
    public NewTopic paymentSuccessTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_SUCCESS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_FAILED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentRefundedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_REFUNDED).partitions(3).replicas(1).build();
    }

    // ── DLTs for topics consumed by payment-service ────────────────────────────
    // The DefaultErrorHandler above routes failed messages to <topic>-dlt.
    // Pre-creating these ensures they exist with the correct partition count and
    // don't get auto-created with broker defaults.

    @Bean
    public NewTopic bookingCancelledDltTopic() {
        return deadLetterTopic(KafkaTopics.BOOKING_CANCELLED);
    }

    @Bean
    public NewTopic bookingCashPaymentDltTopic() {
        return deadLetterTopic(KafkaTopics.BOOKING_CASH_PAYMENT);
    }

    private NewTopic deadLetterTopic(String topic) {
        // 30-day retention gives ops sufficient forensic window; default 7-day broker
        // retention would silently expire poison messages before they can be investigated.
        return TopicBuilder.name(topic + "-dlt")
            .partitions(3)
            .replicas(1)
            .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
            .build();
    }
}
