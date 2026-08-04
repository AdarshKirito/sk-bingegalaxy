package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.Destination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DestinationRepository extends JpaRepository<Destination, String> {

    List<Destination> findByActiveTrueOrderByDisplayNameAsc();

    List<Destination> findByOperatedByProviderCode(String providerCode);

    List<Destination> findByCodeIn(Collection<String> codes);
}
