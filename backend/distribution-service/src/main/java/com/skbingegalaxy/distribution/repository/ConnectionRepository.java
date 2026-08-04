package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.Connection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    List<Connection> findByBingeIdOrderByCreatedAtDesc(Long bingeId);

    Optional<Connection> findByBingeIdAndProviderCodeAndEnvironment(
            Long bingeId, String providerCode, Connection.Environment environment);

    /**
     * Ownership-scoped lookup. Every connection read must be scoped by {@code bingeId} —
     * a bare {@code findById} would let one venue's admin address another's connection,
     * which is the inherited-authorization failure mode this codebase has hit before.
     */
    Optional<Connection> findByIdAndBingeId(Long id, Long bingeId);

    /**
     * Feeds the expiry warning. Some providers rotate credentials on a schedule — Google
     * Actions Center every six months — and an expired credential that stops a channel
     * silently is indistinguishable from a channel with no demand.
     */
    List<Connection> findByCredentialExpiresAtBeforeAndStatusNot(
            LocalDateTime cutoff, Connection.ConnectionStatus excludedStatus);

    List<Connection> findByStatus(Connection.ConnectionStatus status);

    long countByBingeIdAndStatus(Long bingeId, Connection.ConnectionStatus status);
}
