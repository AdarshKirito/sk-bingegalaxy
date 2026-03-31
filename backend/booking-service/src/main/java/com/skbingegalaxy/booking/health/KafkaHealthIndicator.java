package com.skbingegalaxy.booking.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaHealthIndicator extends AbstractHealthIndicator {

    private static final String TOPIC = "booking.created";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            var partitions = kafkaTemplate.partitionsFor(TOPIC);
            if (partitions != null && !partitions.isEmpty()) {
                builder.up()
                        .withDetail("topic", TOPIC)
                        .withDetail("partitions", partitions.size());
            } else {
                builder.down()
                        .withDetail("topic", TOPIC)
                        .withDetail("reason", "No partitions found");
            }
        } catch (Exception e) {
            builder.down(e);
        }
    }
}
