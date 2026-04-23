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

    /**
     * Returns `[rating, customerId]` tuples for every customer review that
     * contributes to a binge's public score.  Used by the weighted-average
     * calculation so we can apply a per-reviewer influence multiplier.
     */
    @Query("SELECT r.rating, r.customerId FROM BookingReview r "
         + "WHERE r.bingeId = :bingeId AND r.reviewerRole = 'CUSTOMER' "
         + "AND r.skipped = false AND r.rating IS NOT NULL AND r.visibleToCustomer = true")
    List<Object[]> ratingAndCustomerIdForBinge(@Param("bingeId") Long bingeId);

    /**
     * Batch lookup of average admin rating for a set of customer ids.
     * Returns `[customerId, avgRating, count]` tuples.
     */
    @Query("SELECT r.customerId, AVG(r.rating), COUNT(r) FROM BookingReview r "
         + "WHERE r.customerId IN :customerIds AND r.reviewerRole = 'ADMIN' "
         + "AND r.rating IS NOT NULL GROUP BY r.customerId")
    List<Object[]> adminRatingStatsForCustomers(@Param("customerIds") java.util.Collection<Long> customerIds);

    /**
     * Returns `[rating, bingeId]` tuples for every admin review recorded
     * against a customer.  Used to weight each admin's private rating
     * by the customer-rated reputation of the binge they represent —
     * admins from highly-reviewed binges carry slightly more signal,
     * admins from poorly-reviewed binges slightly less.  Mirrors the
     * trust model applied to customer reviews.
     */
    @Query("SELECT r.rating, r.bingeId FROM BookingReview r "
         + "WHERE r.customerId = :customerId AND r.reviewerRole = 'ADMIN' "
         + "AND r.rating IS NOT NULL")
    List<Object[]> adminRatingAndBingeForCustomer(@Param("customerId") Long customerId);
}
