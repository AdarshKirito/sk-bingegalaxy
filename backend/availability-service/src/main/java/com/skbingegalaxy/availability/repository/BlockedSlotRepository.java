package com.skbingegalaxy.availability.repository;

import com.skbingegalaxy.availability.entity.BlockedSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BlockedSlotRepository extends JpaRepository<BlockedSlot, Long> {
    List<BlockedSlot> findBySlotDate(LocalDate date);
    boolean existsBySlotDateAndStartHour(LocalDate date, int startHour);
    void deleteBySlotDateAndStartHour(LocalDate date, int startHour);
    List<BlockedSlot> findBySlotDateBetween(LocalDate from, LocalDate to);
}
