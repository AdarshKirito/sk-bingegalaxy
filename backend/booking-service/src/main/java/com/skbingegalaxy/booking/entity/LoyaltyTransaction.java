package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "loyalty_transactions", indexes = {
    @Index(name = "idx_loyalty_txn_account", columnList = "accountId"),
    @Index(name = "idx_loyalty_txn_booking", columnList = "bookingRef")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column(length = 20)
    private String bookingRef;

    /** EARN, REDEEM, EXPIRE, ADJUST */
    @Column(nullable = false, length = 10)
    private String type;

    /** Positive for EARN/ADJUST, negative for REDEEM/EXPIRE. */
    @Column(nullable = false)
    private long points;

    @Column(length = 255)
    private String description;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
