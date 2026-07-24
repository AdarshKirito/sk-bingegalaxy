package com.skbingegalaxy.booking.permission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BingePermissionAuditRepository extends JpaRepository<BingePermissionAudit, Long> {

    List<BingePermissionAudit> findTop50ByBingeIdOrderByCreatedAtDesc(Long bingeId);
}
