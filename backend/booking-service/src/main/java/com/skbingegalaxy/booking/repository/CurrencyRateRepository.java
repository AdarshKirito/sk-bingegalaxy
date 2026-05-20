package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.CurrencyRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CurrencyRateRepository extends JpaRepository<CurrencyRate, String> {

    List<CurrencyRate> findByActiveTrueOrderByCodeAsc();

    Optional<CurrencyRate> findByBaseTrue();
}
