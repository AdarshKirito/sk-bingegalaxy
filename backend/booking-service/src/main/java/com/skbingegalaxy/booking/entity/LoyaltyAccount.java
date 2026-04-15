package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "loyalty_accounts", uniqueConstraints = {
    @UniqueConstraint(name = "uq_loyalty_customer_binge", columnNames = {"customerId", "bingeId"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Long bingeId;

    @Column(nullable = false)
    @Builder.Default
    private long totalPointsEarned = 0;

    @Column(nullable = false)
    @Builder.Default
    private long currentBalance = 0;

    /** BRONZE, SILVER, GOLD, PLATINUM */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String tierLevel = "BRONZE";

    @Version
    private Long version;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
