package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.FxRateLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FxRateLockRepository extends JpaRepository<FxRateLock, Long> {
    Optional<FxRateLock> findByLockToken(String lockToken);
    Optional<FxRateLock> findByLockTokenAndStatus(String lockToken, FxRateLock.Status status);
    List<FxRateLock> findByStatusAndLockedUntilBefore(FxRateLock.Status status, LocalDateTime cutoff);
}
