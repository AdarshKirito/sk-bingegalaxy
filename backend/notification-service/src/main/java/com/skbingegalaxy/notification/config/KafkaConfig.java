package com.skbingegalaxy.notification.config;

import com.skbingegalaxy.common.constants.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
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
     * Retry 3 times with a 2-second backoff, then publish to {@code <topic>-dlt}.
     * Deserialization errors go straight to the DLT — retrying corrupt bytes is futile.
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
    public NewTopic bookingCreatedDltTopic() {
        return deadLetterTopic(KafkaTopics.BOOKING_CREATED);
    }

    @Bean
    public NewTopic bookingCancelledDltTopic() {
        return deadLetterTopic(KafkaTopics.BOOKING_CANCELLED);
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
    public NewTopic notificationSendDltTopic() {
        return deadLetterTopic(KafkaTopics.NOTIFICATION_SEND);
    }

    @Bean
    public NewTopic userRegisteredDltTopic() {
        return deadLetterTopic(KafkaTopics.USER_REGISTERED);
    }

    @Bean
    public NewTopic passwordResetDltTopic() {
        return deadLetterTopic(KafkaTopics.PASSWORD_RESET);
    }

    private NewTopic deadLetterTopic(String topic) {
        return TopicBuilder.name(topic + "-dlt").partitions(3).replicas(1).build();
    }
}
