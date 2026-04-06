package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * CQRS read model — a denormalized projection of a booking's current state,
 * rebuilt purely from the event log. Optimised for read-heavy admin dashboards.
 */
@Entity
@Table(name = "booking_read_model", indexes = {
    @Index(name = "idx_brm_booking_ref", columnList = "bookingRef", unique = true),
    @Index(name = "idx_brm_status", columnList = "status"),
    @Index(name = "idx_brm_customer", columnList = "customerId"),
    @Index(name = "idx_brm_booking_date", columnList = "bookingDate")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BookingReadModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String bookingRef;

    private Long customerId;

    private String status;

    private String paymentStatus;

    private BigDecimal totalAmount;

    private BigDecimal collectedAmount;

    private LocalDate bookingDate;

    private LocalTime startTime;

    private Integer durationMinutes;

    private int numberOfGuests;

    private boolean checkedIn;

    private Long eventTypeId;

    /** Number of events applied to build this projection */
    private int eventCount;

    /** ID of the last event applied */
    private Long lastEventId;

    @UpdateTimestamp
    private LocalDateTime projectedAt;
}
