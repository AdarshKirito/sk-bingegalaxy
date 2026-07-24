package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BingeRepository extends JpaRepository<Binge, Long> {
    List<Binge> findByAdminIdOrderByCreatedAtDesc(Long adminId);
    List<Binge> findAllByOrderByCreatedAtDesc();
    List<Binge> findByActiveTrueOrderByNameAsc();
    boolean existsByNameAndAdminId(String name, Long adminId);

    List<Binge> findByStatusOrderByCreatedAtDesc(BingeApprovalStatus status);

    /**
     * Lightweight approval-status lookup by id (no entity hydration) — used by the
     * BingeApprovalInterceptor to freeze operations on a REJECTED venue. Empty when
     * the binge does not exist.
     */
    @Query("SELECT b.status FROM Binge b WHERE b.id = :id")
    java.util.Optional<BingeApprovalStatus> findStatusById(@Param("id") Long id);

    /**
     * Lightweight ISO-3166 alpha-2 country lookup by id (no entity hydration) —
     * used by the loyalty redemption resolver to value points against the
     * VENUE's country economics. Empty when the binge does not exist.
     */
    @Query("SELECT b.country FROM Binge b WHERE b.id = :id")
    java.util.Optional<String> findCountryById(@Param("id") Long id);

    /**
     * Customer-facing listing: only APPROVED + active binges that have at least
     * one active event type. Hides empty/freshly-created venues so customers
     * never land on a binge they can't actually book.
     */
    @Query("SELECT b FROM Binge b WHERE b.active = true AND b.status = "
            + "com.skbingegalaxy.booking.entity.BingeApprovalStatus.APPROVED "
            + "AND EXISTS (SELECT 1 FROM EventType e WHERE e.bingeId = b.id AND e.active = true) "
            + "ORDER BY b.name ASC")
    List<Binge> findCustomerVisibleBinges();

    /**
     * Proximity stage 1 (bounding box): customer-visible, geocoded venues whose
     * coordinates fall inside a lat/lng box. The {@code latitude}/{@code longitude}
     * range predicates let Postgres use the partial {@code idx_binges_lat_lng} index
     * instead of scanning the table; the service then refines this small candidate set
     * with the exact Haversine distance. Used when the box could be cleanly bounded in
     * longitude.
     */
    @Query("SELECT b FROM Binge b WHERE b.active = true AND b.status = "
            + "com.skbingegalaxy.booking.entity.BingeApprovalStatus.APPROVED "
            + "AND b.latitude IS NOT NULL AND b.longitude IS NOT NULL "
            + "AND b.latitude BETWEEN :minLat AND :maxLat "
            + "AND b.longitude BETWEEN :minLng AND :maxLng "
            + "AND EXISTS (SELECT 1 FROM EventType e WHERE e.bingeId = b.id AND e.active = true)")
    List<Binge> findVisibleGeocodedBingesInBox(@Param("minLat") double minLat,
                                               @Param("maxLat") double maxLat,
                                               @Param("minLng") double minLng,
                                               @Param("maxLng") double maxLng);

    /**
     * Proximity stage 1 fallback (latitude band only): used when the bounding box cannot
     * be cleanly bounded in longitude (near a pole, a globe-spanning radius, or a box
     * crossing the antimeridian). Still index-assisted on latitude; the exact Haversine
     * refine in the service guarantees the final result is correct.
     */
    @Query("SELECT b FROM Binge b WHERE b.active = true AND b.status = "
            + "com.skbingegalaxy.booking.entity.BingeApprovalStatus.APPROVED "
            + "AND b.latitude IS NOT NULL AND b.longitude IS NOT NULL "
            + "AND b.latitude BETWEEN :minLat AND :maxLat "
            + "AND EXISTS (SELECT 1 FROM EventType e WHERE e.bingeId = b.id AND e.active = true)")
    List<Binge> findVisibleGeocodedBingesInLatBand(@Param("minLat") double minLat,
                                                   @Param("maxLat") double maxLat);
}
