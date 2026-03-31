package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Single-row table (id = 1) storing the current operational date.
 * The operational date advances only after a successful nightly audit,
 * so it is decoupled from the wall-clock date.
 */
@Entity
@Table(name = "system_settings")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SystemSettings {

    @Id
    private Long id; // Always 1

    @Column(nullable = false)
    private LocalDate operationalDate;
}
