package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.ResourceLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResourceLockRepository extends JpaRepository<ResourceLock, Long> {

    /**
     * Locate a lock by its (type, id) business key. Resource type is matched
     * case-insensitively at the service layer; we store it as supplied so the
     * audit log shows the exact value the caller used.
     */
    Optional<ResourceLock> findByResourceTypeAndResourceId(String resourceType, String resourceId);

    /**
     * All locks for one resource type. Used by per-page lock viewers
     * (e.g. "show me every locked currency").
     */
    List<ResourceLock> findByResourceType(String resourceType);

    /** Paginated history for the super-admin lock viewer. */
    Page<ResourceLock> findAllByOrderByLockedAtDesc(Pageable pageable);
}
