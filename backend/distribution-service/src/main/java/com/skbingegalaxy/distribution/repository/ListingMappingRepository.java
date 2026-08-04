package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.ListingMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ListingMappingRepository extends JpaRepository<ListingMapping, Long> {

    List<ListingMapping> findByBingeId(Long bingeId);

    List<ListingMapping> findByConnectionDestinationId(Long connectionDestinationId);

    List<ListingMapping> findByConnectionDestinationIdIn(Collection<Long> connectionDestinationIds);

    Optional<ListingMapping> findByConnectionDestinationIdAndEventTypeId(
            Long connectionDestinationId, Long eventTypeId);

    /**
     * Every destination a given event type is live on. Used before a destructive change
     * to that event type: silently altering the duration of something already on sale
     * through a third party is how oversell reaches a traveller.
     */
    List<ListingMapping> findByEventTypeIdAndPublishState(
            Long eventTypeId, ListingMapping.PublishState publishState);

    long countByBingeIdAndPublishState(Long bingeId, ListingMapping.PublishState publishState);
}
