package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.AddOn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AddOnRepository extends JpaRepository<AddOn, Long> {
    List<AddOn> findByActiveTrue();
    List<AddOn> findByCategoryAndActiveTrue(String category);
    List<AddOn> findByBingeIdAndActiveTrue(Long bingeId);
    List<AddOn> findByBingeId(Long bingeId);

    @Query("SELECT a FROM AddOn a WHERE (a.bingeId = :bingeId OR a.bingeId IS NULL) AND a.active = true")
    List<AddOn> findByBingeIdOrGlobalAndActiveTrue(@Param("bingeId") Long bingeId);

    @Query("SELECT a FROM AddOn a WHERE a.bingeId = :bingeId OR a.bingeId IS NULL")
    List<AddOn> findByBingeIdOrGlobal(@Param("bingeId") Long bingeId);
}
