package com.skbingegalaxy.notification.config;

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
