package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.PasswordHistoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistoryEntry, Long> {

    @Query("SELECT p FROM PasswordHistoryEntry p WHERE p.userId = :userId ORDER BY p.createdAt DESC")
    List<PasswordHistoryEntry> findRecent(@Param("userId") Long userId, Pageable pageable);
}
