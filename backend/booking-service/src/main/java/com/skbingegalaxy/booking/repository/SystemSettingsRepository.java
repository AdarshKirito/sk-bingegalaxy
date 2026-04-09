package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.SystemSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemSettingsRepository extends JpaRepository<SystemSettings, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SystemSettings s WHERE s.id = :id")
    Optional<SystemSettings> findByIdForUpdate(@Param("id") Long id);
}
