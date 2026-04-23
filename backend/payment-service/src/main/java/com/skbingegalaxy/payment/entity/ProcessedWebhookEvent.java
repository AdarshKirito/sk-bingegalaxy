package com.skbingegalaxy.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Persistent dedup record for gateway webhook / callback deliveries.
 *
 * <p>{@code eventId} is derived from a stable, gateway-assigned identity
 * (for Razorpay: {@code orderId:paymentId:status}). Any duplicate or
 * out-of-order delivery with the same tuple short-circuits before
 * performing side effects, closing the gap Stripe and Adyen both warn
 * about: "duplicate deliveries can happen — handle safely."
 */
@Entity
@Table(name = "processed_webhook_event")
@IdClass(ProcessedWebhookEvent.Pk.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProcessedWebhookEvent {

    @Id
    @Column(name = "event_id", length = 128, nullable = false)
    private String eventId;

    @Id
    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class Pk implements Serializable {
        private String eventId;
        private String provider;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(eventId, pk.eventId)
                && Objects.equals(provider, pk.provider);
        }

        @Override
        public int hashCode() { return Objects.hash(eventId, provider); }
    }
}
