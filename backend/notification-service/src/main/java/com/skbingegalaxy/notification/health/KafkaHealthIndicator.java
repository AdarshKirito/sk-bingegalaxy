package com.skbingegalaxy.notification.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaHealthIndicator extends AbstractHealthIndicator {

    private static final java.util.List<String> TOPICS = java.util.List.of(
        "booking.created", "booking.confirmed", "booking.cancelled",
        "payment.success", "payment.failed", "payment.refunded",
        "notification.send", "user.registered", "password.reset"
    );

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            java.util.List<String> reachable = new java.util.ArrayList<>();
            java.util.List<String> unreachable = new java.util.ArrayList<>();
            for (String topic : TOPICS) {
                try {
                    var partitions = kafkaTemplate.partitionsFor(topic);
                    if (partitions != null && !partitions.isEmpty()) {
                        reachable.add(topic);
                    } else {
                        unreachable.add(topic);
                    }
                } catch (Exception ignored) {
                    unreachable.add(topic);
                }
            }
            if (unreachable.isEmpty()) {
                builder.up()
                        .withDetail("topics", reachable.size())
                        .withDetail("reachable", reachable);
            } else {
                builder.down()
                        .withDetail("reachable", reachable)
                        .withDetail("unreachable", unreachable);
            }
        } catch (Exception e) {
            builder.down(e);
        }
    }
}
