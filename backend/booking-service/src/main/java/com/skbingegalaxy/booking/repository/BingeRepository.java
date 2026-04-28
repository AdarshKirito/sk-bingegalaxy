package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BingeRepository extends JpaRepository<Binge, Long> {
    List<Binge> findByAdminIdOrderByCreatedAtDesc(Long adminId);
    List<Binge> findAllByOrderByCreatedAtDesc();
    List<Binge> findByActiveTrueOrderByNameAsc();
    boolean existsByNameAndAdminId(String name, Long adminId);

    List<Binge> findByStatusOrderByCreatedAtDesc(BingeApprovalStatus status);

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
}
