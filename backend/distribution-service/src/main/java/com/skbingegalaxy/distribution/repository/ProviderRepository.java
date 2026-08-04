package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProviderRepository extends JpaRepository<Provider, String> {

    /**
     * Only active providers are connectable. Every real provider is seeded inactive, so
     * this returns just the simulator until a super-admin deliberately turns one on.
     */
    List<Provider> findByActiveTrueOrderByDisplayNameAsc();

    List<Provider> findAllByOrderByDisplayNameAsc();
}
