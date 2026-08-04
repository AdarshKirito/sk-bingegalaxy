package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConnectionDestinationRepository
        extends JpaRepository<ConnectionDestination, Long> {

    List<ConnectionDestination> findByConnectionId(Long connectionId);

    /** One query for a venue's whole console rather than one per connection. */
    List<ConnectionDestination> findByConnectionIdIn(Collection<Long> connectionIds);

    Optional<ConnectionDestination> findByConnectionIdAndDestinationCode(
            Long connectionId, String destinationCode);

    List<ConnectionDestination> findByConnectionIdAndEnabledTrue(Long connectionId);
}
