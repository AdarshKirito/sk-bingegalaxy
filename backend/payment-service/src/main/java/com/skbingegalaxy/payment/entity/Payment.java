package com.skbingegalaxy.payment.entity;

import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.common.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments", indexes = {
    @Index(name = "idx_payment_booking_ref", columnList = "bookingRef"),
    @Index(name = "idx_payment_transaction_id", columnList = "transactionId", unique = true),
    @Index(name = "idx_payment_customer_id", columnList = "customerId")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String bookingRef;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false, unique = true)
    private String transactionId;

    private String gatewayOrderId;

    private String gatewayPaymentId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(precision = 10, scale = 2)
    private BigDecimal gatewayFee;

    @Column(precision = 10, scale = 2)
    private BigDecimal tax;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String currency;

    private String gatewayResponse;

    private String failureReason;

    private LocalDateTime paidAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
