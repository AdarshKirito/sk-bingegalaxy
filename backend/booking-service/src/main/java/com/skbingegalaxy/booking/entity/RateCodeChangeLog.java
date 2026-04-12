package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "rate_code_change_log", indexes = {
    @Index(name = "idx_rccl_customer", columnList = "customerId"),
    @Index(name = "idx_rccl_binge", columnList = "bingeId")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RateCodeChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    private Long bingeId;

    private Long previousRateCodeId;

    @Column(length = 100)
    private String previousRateCodeName;

    private Long newRateCodeId;

    @Column(length = 100)
    private String newRateCodeName;

    @Column(length = 30)
    private String changeType; // ASSIGN, REASSIGN, UNASSIGN, BULK_ASSIGN

    private Long changedByAdminId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime changedAt;
}
