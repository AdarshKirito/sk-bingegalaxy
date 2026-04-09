package com.skbingegalaxy.notification.config;

import com.skbingegalaxy.common.constants.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
@Configuration
public class KafkaConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        var backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30000L);
        backOff.setMaxElapsedTime(120000L);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                org.apache.kafka.common.errors.SerializationException.class,
                org.springframework.messaging.converter.MessageConversionException.class
        );
        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka retry attempt {} for topic={} key={}", deliveryAttempt, record.topic(), record.key(), ex));
        return handler;
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
