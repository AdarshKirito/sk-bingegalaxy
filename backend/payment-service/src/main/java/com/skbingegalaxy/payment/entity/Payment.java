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
    @Index(name = "idx_payment_customer_id", columnList = "customerId"),
    @Index(name = "idx_payment_binge_id", columnList = "bingeId")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false)
    private String bookingRef;

    @Column(nullable = false)
    private Long customerId;

    private Long bingeId;

    @Column(nullable = false, unique = true)
    private String transactionId;

    private String gatewayOrderId;

    private String gatewayPaymentId;

    /**
     * Which gateway actually handled this charge ("razorpay", "stripe"), or null
     * for offline/simulated payments.
     *
     * <p>The {@code provider_name} column has existed since V9 but was never mapped
     * or written, so refunds had no way to know where the money went and always
     * went to Razorpay. Refunding through the wrong gateway either fails outright
     * or draws from the wrong account, so this is authoritative for refund routing.
     */
    @Column(name = "provider_name", length = 40)
    private String providerName;

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

    private String customerEmail;

    private String customerName;

    private String customerPhone;

    /** E.164 dial prefix (e.g. "+91"). */
    @Column(name = "customer_phone_country_code", length = 8)
    private String customerPhoneCountryCode;

    private String gatewayResponse;

    private String failureReason;

    private LocalDateTime paidAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
