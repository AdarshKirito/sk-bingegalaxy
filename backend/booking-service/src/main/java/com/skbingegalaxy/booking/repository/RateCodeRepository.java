package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.RateCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RateCodeRepository extends JpaRepository<RateCode, Long> {
    Optional<RateCode> findByName(String name);
    boolean existsByName(String name);
    List<RateCode> findByActiveTrue();
    List<RateCode> findByBingeId(Long bingeId);
    List<RateCode> findByBingeIdAndActiveTrue(Long bingeId);
    boolean existsByNameAndBingeId(String name, Long bingeId);
}
