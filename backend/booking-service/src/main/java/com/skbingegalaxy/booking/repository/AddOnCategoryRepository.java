package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.AddOnCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddOnCategoryRepository extends JpaRepository<AddOnCategory, Long> {

    List<AddOnCategory> findByBingeIdIsNull();

    List<AddOnCategory> findByBingeId(Long bingeId);

    @Query("""
        SELECT c FROM AddOnCategory c
        WHERE  c.active = true
          AND  (c.bingeId = :bingeId OR c.bingeId IS NULL)
        ORDER BY c.sortOrder ASC, c.name ASC
        """)
    List<AddOnCategory> findVisibleForBinge(@Param("bingeId") Long bingeId);

    Optional<AddOnCategory> findByIdAndBingeId(Long id, Long bingeId);
    Optional<AddOnCategory> findByIdAndBingeIdIsNull(Long id);

    boolean existsByBingeIdAndNameIgnoreCase(Long bingeId, String name);
    boolean existsByBingeIdIsNullAndNameIgnoreCase(String name);

    Optional<AddOnCategory> findFirstByNameIgnoreCaseAndBingeId(String name, Long bingeId);
    Optional<AddOnCategory> findFirstByNameIgnoreCaseAndBingeIdIsNull(String name);
}
