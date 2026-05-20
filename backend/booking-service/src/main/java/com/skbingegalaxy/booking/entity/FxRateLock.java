package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A short-lived FX rate lock. The customer "locks" a quoted FX rate when
 * they enter checkout; the lock expires after a configurable TTL (default
 * 15 minutes). When the customer pays, the lock is consumed and the
 * locked rate becomes the rate persisted on the booking + invoice.
 *
 * <p>This guarantees price stability across the
 * preview → confirm → pay round-trip even when underlying FX rates move.
 */
@Entity
@Table(name = "fx_rate_locks", indexes = {
    @Index(name = "idx_fx_locks_status", columnList = "status"),
    @Index(name = "idx_fx_locks_locked_until", columnList = "locked_until"),
    @Index(name = "idx_fx_locks_customer", columnList = "customer_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder(toBuilder = true)
public class FxRateLock {

    public enum Status { ACTIVE, CONSUMED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lock_token", nullable = false, unique = true, length = 64)
    private String lockToken;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "booking_ref", length = 64)
    private String bookingRef;

    @Column(name = "from_currency", nullable = false, length = 8)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 8)
    private String toCurrency;

    @Column(name = "fx_rate", nullable = false, precision = 20, scale = 10)
    private BigDecimal fxRate;

    @Column(name = "fx_source", nullable = false, length = 40)
    @Builder.Default
    private String fxSource = "MANUAL";

    @Column(name = "base_amount", precision = 14, scale = 4)
    private BigDecimal baseAmount;

    @Column(name = "converted_amount", precision = 14, scale = 4)
    private BigDecimal convertedAmount;

    @CreationTimestamp
    @Column(name = "locked_at", updatable = false)
    private LocalDateTime lockedAt;

    @Column(name = "locked_until", nullable = false)
    private LocalDateTime lockedUntil;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;
}
