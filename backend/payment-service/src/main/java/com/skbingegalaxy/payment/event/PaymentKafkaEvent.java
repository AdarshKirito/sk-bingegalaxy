package com.skbingegalaxy.payment.event;

import com.skbingegalaxy.common.event.PaymentEvent;

/**
 * Spring application event that carries a {@link PaymentEvent} and a
 * Kafka topic name.  Published inside {@code @Transactional} methods;
 * the actual Kafka send happens AFTER the transaction commits via
 * {@link PaymentKafkaPublisher}.
 */
public record PaymentKafkaEvent(String topic, String key, PaymentEvent payload) {
}
