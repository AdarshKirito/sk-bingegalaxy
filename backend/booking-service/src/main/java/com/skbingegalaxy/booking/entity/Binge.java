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

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
