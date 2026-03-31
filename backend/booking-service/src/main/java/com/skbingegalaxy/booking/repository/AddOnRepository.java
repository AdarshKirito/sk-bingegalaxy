package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.AddOn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AddOnRepository extends JpaRepository<AddOn, Long> {
    List<AddOn> findByActiveTrue();
    List<AddOn> findByCategoryAndActiveTrue(String category);
}
