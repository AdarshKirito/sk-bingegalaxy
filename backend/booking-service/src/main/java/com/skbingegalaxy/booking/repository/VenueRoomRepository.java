package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.VenueRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VenueRoomRepository extends JpaRepository<VenueRoom, Long> {

    List<VenueRoom> findByBingeIdOrderBySortOrderAsc(Long bingeId);

    List<VenueRoom> findByBingeIdAndActiveTrueOrderBySortOrderAsc(Long bingeId);

    Optional<VenueRoom> findByIdAndBingeId(Long id, Long bingeId);
}
