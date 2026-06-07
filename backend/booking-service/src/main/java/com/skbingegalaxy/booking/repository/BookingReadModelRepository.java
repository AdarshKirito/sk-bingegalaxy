package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingReadModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BookingReadModelRepository extends JpaRepository<BookingReadModel, Long> {

    Optional<BookingReadModel> findByBookingRef(String bookingRef);

    void deleteByBookingRef(String bookingRef);

    /** Booking refs in read model that are NOT in the source bookings table (orphaned projections). */
    @Query(value = """
        SELECT brm.booking_ref FROM booking_read_model brm
        WHERE NOT EXISTS (SELECT 1 FROM bookings b WHERE b.booking_ref = brm.booking_ref)
        LIMIT 200
        """, nativeQuery = true)
    List<String> findOrphanedProjections();

    /** Booking refs in source table whose status differs from the projection. */
    @Query(value = """
        SELECT b.booking_ref FROM bookings b
        JOIN booking_read_model brm ON b.booking_ref = brm.booking_ref
        WHERE b.status <> brm.status
        LIMIT 200
        """, nativeQuery = true)
    List<String> findStatusMismatchedRefs();

    /** Booking refs that exist in source but have no projection at all. */
    @Query(value = """
        SELECT b.booking_ref FROM bookings b
        WHERE NOT EXISTS (SELECT 1 FROM booking_read_model brm WHERE brm.booking_ref = b.booking_ref)
        LIMIT 200
        """, nativeQuery = true)
    List<String> findBookingsWithoutProjection();
}
