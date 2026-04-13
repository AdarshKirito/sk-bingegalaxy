package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "booking_reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long bingeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(nullable = false, length = 20)
    private String bookingRef;

    @Column(nullable = false)
    private Long customerId;

    private Long adminId;

    @Column(nullable = false, length = 20)
    private String reviewerRole;

    private Integer rating;

    @Column(length = 1200)
    private String comment;

    @Column(nullable = false)
    @Builder.Default
    private boolean skipped = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean visibleToCustomer = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
