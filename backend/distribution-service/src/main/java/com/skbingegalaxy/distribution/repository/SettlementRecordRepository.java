package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.SettlementRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {

    Optional<SettlementRecord> findByBookingRefAndDestinationCode(
            String bookingRef, String destinationCode);

    List<SettlementRecord> findByBookingRef(String bookingRef);

    List<SettlementRecord> findByBingeIdAndSettlementStatusIn(
            Long bingeId, Collection<SettlementRecord.SettlementStatus> statuses);

    /**
     * What a venue is owed but has not been paid.
     *
     * <p>Presented as a <em>receivable</em>, never as revenue in the bank. Viator pays
     * the net rate only after the experience completes, so channel money is routinely
     * weeks away at the moment the booking appears.
     */
    List<SettlementRecord> findByBingeIdAndExpectedPayoutAtBeforeAndSettlementStatusIn(
            Long bingeId, LocalDate cutoff,
            Collection<SettlementRecord.SettlementStatus> statuses);

    List<SettlementRecord> findByBingeIdAndReconciliationStatus(
            Long bingeId, SettlementRecord.ReconciliationStatus reconciliationStatus);
}
