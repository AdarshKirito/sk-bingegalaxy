package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SagaStateRepository extends JpaRepository<SagaState, Long> {

    Optional<SagaState> findByBookingRef(String bookingRef);

    List<SagaState> findBySagaStatus(SagaState.SagaStatus status);
}
