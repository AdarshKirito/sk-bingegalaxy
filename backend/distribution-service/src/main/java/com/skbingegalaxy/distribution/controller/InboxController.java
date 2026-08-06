package com.skbingegalaxy.distribution.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.dto.InboxEntryDto;
import com.skbingegalaxy.distribution.service.ReservationInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Recovery console read surface for the reservation inbox (slice 6).
 *
 * <p><b>Read and requeue only.</b> There is deliberately no ingestion endpoint here:
 * that is the seam a real provider POSTs to, and its shape — authentication, payload,
 * acknowledgement semantics — is decided by WHICH connector goes first. Building it now
 * would be guessing at the caller, and a wrong guess in an inbound seam is expensive to
 * unpick once a provider is live against it.
 */
@RestController
@RequestMapping("/api/v1/distribution")
@RequiredArgsConstructor
public class InboxController {

    private final ReservationInboxService inboxService;
    private final com.skbingegalaxy.distribution.service.DistributionHealthService healthService;

    private Long requireBinge(Long bingeId) {
        if (bingeId == null) {
            throw new BusinessException("Select a venue before viewing the reservation inbox.",
                HttpStatus.BAD_REQUEST);
        }
        return bingeId;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<com.skbingegalaxy.distribution.dto.DistributionHealthDto>> health(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId) {
        return ResponseEntity.ok(ApiResponse.ok(healthService.forBinge(requireBinge(bingeId))));
    }

    @GetMapping("/inbox")
    public ResponseEntity<ApiResponse<List<InboxEntryDto>>> list(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(
            inboxService.listForBinge(requireBinge(bingeId), limit)));
    }

    @PostMapping("/inbox/{id}/retry")
    public ResponseEntity<ApiResponse<Void>> retry(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id) {
        inboxService.retry(requireBinge(bingeId), id);
        return ResponseEntity.ok(ApiResponse.ok("Message requeued", null));
    }
}
