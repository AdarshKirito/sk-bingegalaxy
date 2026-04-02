package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.Binge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BingeRepository extends JpaRepository<Binge, Long> {
    List<Binge> findByAdminIdOrderByCreatedAtDesc(Long adminId);
    List<Binge> findAllByOrderByCreatedAtDesc();
    List<Binge> findByActiveTrueOrderByNameAsc();
    boolean existsByNameAndAdminId(String name, Long adminId);
}
