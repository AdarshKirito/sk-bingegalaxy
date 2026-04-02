package com.skbingegalaxy.availability.repository;

import com.skbingegalaxy.availability.entity.BlockedDate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BlockedDateRepository extends JpaRepository<BlockedDate, Long> {
    boolean existsByBlockedDate(LocalDate date);
    boolean existsByBingeIdAndBlockedDate(Long bingeId, LocalDate date);
    Optional<BlockedDate> findByBlockedDate(LocalDate date);
    List<BlockedDate> findByBlockedDateBetween(LocalDate from, LocalDate to);
    List<BlockedDate> findByBingeIdAndBlockedDateBetween(Long bingeId, LocalDate from, LocalDate to);
    void deleteByBlockedDate(LocalDate date);
    void deleteByBingeIdAndBlockedDate(Long bingeId, LocalDate date);
    List<BlockedDate> findByBingeId(Long bingeId);
}
