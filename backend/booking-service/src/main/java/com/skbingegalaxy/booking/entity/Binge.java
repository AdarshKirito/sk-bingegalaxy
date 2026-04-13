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

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
