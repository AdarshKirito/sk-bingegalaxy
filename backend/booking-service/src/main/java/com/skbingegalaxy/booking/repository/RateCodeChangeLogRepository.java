package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.RateCodeChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RateCodeChangeLogRepository extends JpaRepository<RateCodeChangeLog, Long> {

    List<RateCodeChangeLog> findByCustomerIdAndBingeIdOrderByChangedAtDesc(Long customerId, Long bingeId);

    Page<RateCodeChangeLog> findByCustomerIdAndBingeId(Long customerId, Long bingeId, Pageable pageable);

    List<RateCodeChangeLog> findByCustomerIdOrderByChangedAtDesc(Long customerId);
}
