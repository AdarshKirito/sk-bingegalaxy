package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.SyncState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SyncStateRepository extends JpaRepository<SyncState, Long> {

    Optional<SyncState> findByConnectionDestinationId(Long connectionDestinationId);

    List<SyncState> findByConnectionDestinationIdIn(Collection<Long> connectionDestinationIds);

    /**
     * Everything the provider is about to consider stale. {@code stale_after} is set
     * ahead of the provider's own deadline — Google delists at 30 days, so this is
     * populated at 21 — because an alarm that fires after the delisting is not an alarm.
     */
    List<SyncState> findByStaleAfterBefore(LocalDateTime cutoff);

    List<SyncState> findByConsecutiveFailuresGreaterThan(int threshold);
}
