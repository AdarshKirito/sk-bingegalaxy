package com.skbingegalaxy.availability.repository;

import com.skbingegalaxy.availability.entity.BlockedSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BlockedSlotRepository extends JpaRepository<BlockedSlot, Long> {
    List<BlockedSlot> findBySlotDate(LocalDate date);
    List<BlockedSlot> findByBingeIdAndSlotDate(Long bingeId, LocalDate date);
    boolean existsBySlotDateAndStartHour(LocalDate date, int startHour);
    boolean existsByBingeIdAndSlotDateAndStartHour(Long bingeId, LocalDate date, int startHour);
    void deleteBySlotDateAndStartHour(LocalDate date, int startHour);
    void deleteByBingeIdAndSlotDateAndStartHour(Long bingeId, LocalDate date, int startHour);
    List<BlockedSlot> findBySlotDateBetween(LocalDate from, LocalDate to);
    List<BlockedSlot> findByBingeIdAndSlotDateBetween(Long bingeId, LocalDate from, LocalDate to);
    List<BlockedSlot> findByBingeId(Long bingeId);
}
