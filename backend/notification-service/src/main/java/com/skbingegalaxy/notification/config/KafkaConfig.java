package com.skbingegalaxy.notification.config;

import com.skbingegalaxy.common.constants.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Slf4j
@Configuration
public class KafkaConfig {

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
