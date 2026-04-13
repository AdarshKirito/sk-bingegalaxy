package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingReviewRepository extends JpaRepository<BookingReview, Long> {
    Optional<BookingReview> findByBookingRefAndCustomerIdAndReviewerRole(String bookingRef, Long customerId, String reviewerRole);
    Optional<BookingReview> findByBookingRefAndAdminIdAndReviewerRole(String bookingRef, Long adminId, String reviewerRole);
    List<BookingReview> findByBookingRefOrderByCreatedAtDesc(String bookingRef);
    List<BookingReview> findByCustomerIdAndReviewerRoleOrderByCreatedAtDesc(Long customerId, String reviewerRole);

    // Binge-level: public customer reviews (non-skipped, visible, with rating)
    Page<BookingReview> findByBingeIdAndReviewerRoleAndSkippedFalseAndVisibleToCustomerTrueAndRatingIsNotNull(
        Long bingeId, String reviewerRole, Pageable pageable);

    // Binge-level aggregate stats
    @Query("SELECT COUNT(r) FROM BookingReview r WHERE r.bingeId = :bingeId AND r.reviewerRole = 'CUSTOMER' AND r.skipped = false AND r.rating IS NOT NULL")
    long countBingeCustomerReviews(@Param("bingeId") Long bingeId);

    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM BookingReview r WHERE r.bingeId = :bingeId AND r.reviewerRole = 'CUSTOMER' AND r.skipped = false AND r.rating IS NOT NULL")
    double averageBingeRating(@Param("bingeId") Long bingeId);

    @Query("SELECT r.rating, COUNT(r) FROM BookingReview r WHERE r.bingeId = :bingeId AND r.reviewerRole = 'CUSTOMER' AND r.skipped = false AND r.rating IS NOT NULL GROUP BY r.rating ORDER BY r.rating DESC")
    List<Object[]> ratingDistribution(@Param("bingeId") Long bingeId);

    // Admin-level: all customer reviews for a specific customer across binges
    Page<BookingReview> findByCustomerIdAndReviewerRoleAndSkippedFalseAndRatingIsNotNull(
        Long customerId, String reviewerRole, Pageable pageable);

    // Admin-level: average rating for a customer (admin reviews)
    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM BookingReview r WHERE r.customerId = :customerId AND r.reviewerRole = 'ADMIN' AND r.rating IS NOT NULL")
    double averageAdminRatingForCustomer(@Param("customerId") Long customerId);

    @Query("SELECT COUNT(r) FROM BookingReview r WHERE r.customerId = :customerId AND r.reviewerRole = 'ADMIN' AND r.rating IS NOT NULL")
    long countAdminReviewsForCustomer(@Param("customerId") Long customerId);
}
