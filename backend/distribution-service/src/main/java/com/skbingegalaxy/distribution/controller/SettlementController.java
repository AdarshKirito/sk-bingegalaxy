package com.skbingegalaxy.distribution.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.dto.SettlementDto;
import com.skbingegalaxy.distribution.entity.SettlementRecord;
import com.skbingegalaxy.distribution.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Money a channel owes this venue (slice 6).
 *
 * <p>Recording a payout is an <b>ADMIN</b> action rather than a super-admin one: it is
 * the venue's own money and the venue's own bank statement being reconciled. Restricting
 * it to the platform would mean nobody who can see the statement is allowed to record it.
 */
@RestController
@RequestMapping("/api/v1/distribution")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    private Long requireBinge(Long bingeId) {
        if (bingeId == null) {
            throw new BusinessException("Select a venue before viewing settlements.",
                HttpStatus.BAD_REQUEST);
        }
        return bingeId;
    }

    @GetMapping("/settlements")
    public ResponseEntity<ApiResponse<List<SettlementDto>>> outstanding(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId) {
        LocalDate today = LocalDate.now();
        List<SettlementDto> rows = settlementService.outstandingForBinge(requireBinge(bingeId))
            .stream().map(r -> toDto(r, today)).toList();
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    /**
     * Outstanding per currency. Returned as a map rather than a single total because
     * summing across currencies would be a confident, meaningless number.
     */
    @GetMapping("/settlements/totals")
    public ResponseEntity<ApiResponse<Map<String, Long>>> totals(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId) {
        return ResponseEntity.ok(ApiResponse.ok(
            settlementService.outstandingByCurrency(requireBinge(bingeId))));
    }

    @PostMapping("/settlements/{id}/payout")
    public ResponseEntity<ApiResponse<SettlementDto>> recordPayout(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id,
            @RequestParam long actualMinor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn) {
        SettlementRecord saved = settlementService.recordPayout(
            requireBinge(bingeId), id, actualMinor, paidOn == null ? LocalDate.now() : paidOn);
        return ResponseEntity.ok(ApiResponse.ok("Payout recorded", toDto(saved, LocalDate.now())));
    }

    private static SettlementDto toDto(SettlementRecord r, LocalDate today) {
        return SettlementDto.builder()
            .id(r.getId())
            .bookingRef(r.getBookingRef())
            .destinationCode(r.getDestinationCode())
            .settlementCurrency(r.getSettlementCurrency())
            .grossMinor(r.getGrossMinor())
            .commissionMinor(r.getCommissionMinor())
            .outstandingMinor(r.getOutstandingMinor())
            .collectedBy(r.getCollectedBy())
            .settlementStatus(r.getSettlementStatus())
            .reconciliationStatus(r.getReconciliationStatus())
            .expectedPayoutAt(r.getExpectedPayoutAt())
            .actualPayoutAt(r.getActualPayoutAt())
            .actualPayoutMinor(r.getActualPayoutMinor())
            .varianceMinor(r.getVarianceMinor())
            // Derived server-side so every caller agrees on what "overdue" means, rather
            // than each client re-deriving it from a date and getting timezones wrong.
            .overdue(r.getExpectedPayoutAt() != null
                  && r.getExpectedPayoutAt().isBefore(today)
                  && r.getActualPayoutAt() == null)
            .build();
    }
}
