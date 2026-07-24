package com.skbingegalaxy.booking.permission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BingeModulePermissionRepository extends JpaRepository<BingeModulePermission, Long> {

    List<BingeModulePermission> findByBingeId(Long bingeId);

    List<BingeModulePermission> findByBingeIdAndUserId(Long bingeId, Long userId);

    Optional<BingeModulePermission> findByBingeIdAndUserIdAndModuleKeyAndActionKey(
        Long bingeId, Long userId, String moduleKey, String actionKey);
}
