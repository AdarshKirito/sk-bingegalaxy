package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.ProviderCapability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProviderCapabilityRepository
        extends JpaRepository<ProviderCapability, ProviderCapability.Pk> {

    List<ProviderCapability> findByProviderCode(String providerCode);

    /**
     * Bulk load for the console, which renders many providers at once.
     *
     * <p>Returns <b>all</b> declared rows rather than only the enabled ones: a row with
     * {@code enabled = false} carries the evidence in {@code notes} explaining why the
     * control is greyed out, and "UNVERIFIED — fail closed" is more useful to an operator
     * than a silently missing button.
     */
    List<ProviderCapability> findByProviderCodeIn(Collection<String> providerCodes);
}
