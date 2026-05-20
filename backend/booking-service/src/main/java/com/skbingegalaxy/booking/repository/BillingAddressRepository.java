package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BillingAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingAddressRepository extends JpaRepository<BillingAddress, Long> {
    List<BillingAddress> findByCustomerIdOrderByIdDesc(Long customerId);
}
