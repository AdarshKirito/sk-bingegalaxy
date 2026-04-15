package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "binges")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Binge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false)
    private Long adminId;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Per-binge operational date – advances only after a successful audit */
    @Column
    private LocalDate operationalDate;

    @Column(columnDefinition = "TEXT")
    private String customerDashboardConfigJson;

    @Column(columnDefinition = "TEXT")
    private String customerAboutConfigJson;

    @Column(length = 150)
    private String supportEmail;

    @Column(length = 20)
    private String supportPhone;

    @Column(length = 20)
    private String supportWhatsapp;

    @Column(nullable = false)
    @Builder.Default
    private boolean customerCancellationEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private int customerCancellationCutoffMinutes = 180;

    /** Maximum concurrent bookings per time slot. Null = unlimited. */
    @Column
    private Integer maxConcurrentBookings;

    // ── Loyalty program ──────────────────────────────────────
    @Column(nullable = false)
    @Builder.Default
    private boolean loyaltyEnabled = false;

    /** How many loyalty points customers earn per ₹1 spent. E.g. 10 = 10 pts/₹ */
    @Column(nullable = false)
    @Builder.Default
    private int loyaltyPointsPerRupee = 10;

    /** Points value when redeemed: how many points = ₹1 discount. E.g. 100 = 100 pts = ₹1 */
    @Column(nullable = false)
    @Builder.Default
    private int loyaltyRedemptionRate = 100;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
