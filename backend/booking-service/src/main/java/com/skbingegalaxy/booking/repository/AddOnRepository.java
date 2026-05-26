package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.AddOn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddOnRepository extends JpaRepository<AddOn, Long> {
    boolean existsByBingeId(Long bingeId);
    List<AddOn> findByActiveTrue();
    List<AddOn> findByBingeIdAndActiveTrue(Long bingeId);
    List<AddOn> findByBingeId(Long bingeId);
    Optional<AddOn> findByIdAndBingeId(Long id, Long bingeId);
    Optional<AddOn> findByIdAndBingeIdIsNull(Long id);

    // Guard for AddOnCategory hard-delete: tells callers whether any AddOn
    // still references the category. With add_ons.category_id NOT NULL +
    // FK ON DELETE RESTRICT (V59 + V60), deleting a referenced category
    // otherwise fails at the DB layer with a confusing constraint error.
    long countByCategoryId(Long categoryId);

    @Query("SELECT a FROM AddOn a WHERE (a.bingeId = :bingeId OR a.bingeId IS NULL) AND a.active = true")
    List<AddOn> findByBingeIdOrGlobalAndActiveTrue(@Param("bingeId") Long bingeId);

    @Query("SELECT a FROM AddOn a WHERE a.bingeId = :bingeId OR a.bingeId IS NULL")
    List<AddOn> findByBingeIdOrGlobal(@Param("bingeId") Long bingeId);

    @Query("SELECT a FROM AddOn a WHERE a.id = :id AND (a.bingeId = :bingeId OR a.bingeId IS NULL)")
    Optional<AddOn> findAccessibleById(@Param("id") Long id, @Param("bingeId") Long bingeId);
}
