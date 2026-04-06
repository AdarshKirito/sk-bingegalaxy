package com.skbingegalaxy.auth.entity;

import com.skbingegalaxy.common.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
    @UniqueConstraint(name = "uk_users_phone", columnNames = "phone")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(unique = true, length = 15)
    private String phone;

    @Column(length = 100)
    private String preferredExperience;

    @Column(length = 120)
    private String vibePreference;

    @Column
    @Builder.Default
    private Integer reminderLeadDays = 14;

    @Column(length = 20)
    private String birthdayMonth;

    @Column(length = 20)
    private String anniversaryMonth;

    @Column(length = 20)
    @Builder.Default
    private String notificationChannel = "WHATSAPP";

    @Column
    @Builder.Default
    private Boolean receivesOffers = true;

    @Column
    @Builder.Default
    private Boolean weekendAlerts = true;

    @Column
    @Builder.Default
    private Boolean conciergeSupport = true;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
