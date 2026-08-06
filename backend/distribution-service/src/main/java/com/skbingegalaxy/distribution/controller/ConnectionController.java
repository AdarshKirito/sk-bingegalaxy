package com.skbingegalaxy.distribution.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.dto.*;
import com.skbingegalaxy.distribution.service.ConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Venue-facing distribution connections (slice 3).
 *
 * <p><b>{@code bingeId} comes from the {@code X-Binge-Id} header the gateway sets, never
 * from a path or body parameter.</b> A connection is a venue's commercial relationship
 * with a provider, so a caller-supplied id would let one venue pause a competitor's
 * sales channel — a worse outcome than an ordinary IDOR.
 */
@RestController
@RequestMapping("/api/v1/distribution")
@RequiredArgsConstructor
public class ConnectionController {

    private final ConnectionService connectionService;

    private Long requireBinge(Long bingeId) {
        if (bingeId == null) {
            throw new BusinessException(
                "Select a venue before managing distribution connections.",
                HttpStatus.BAD_REQUEST);
        }
        return bingeId;
    }

    /** Providers this venue may connect to — today, only ones a super-admin activated. */
    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<List<ProviderDto>>> listProviders() {
        return ResponseEntity.ok(ApiResponse.ok(connectionService.listConnectableProviders()));
    }

    @GetMapping("/connections")
    public ResponseEntity<ApiResponse<List<ConnectionDto>>> list(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId) {
        return ResponseEntity.ok(ApiResponse.ok(
            connectionService.listForBinge(requireBinge(bingeId))));
    }

    @PostMapping("/connections")
    public ResponseEntity<ApiResponse<ConnectionDto>> create(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @RequestHeader(value = "X-User-Id", required = false) Long actorId,
            @Valid @RequestBody CreateConnectionRequest request) {
        ConnectionDto created = connectionService.create(requireBinge(bingeId), actorId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Connection created", created));
    }

    /**
     * Point a connection at a destination. Covered by the existing
     * {@code /connections/**} security matcher, so no new allow-list entry is needed —
     * and therefore no chance of the matcher mismatch this codebase has hit before.
     */
    @PostMapping("/connections/{id}/destinations")
    public ResponseEntity<ApiResponse<ConnectionDestinationDto>> enableDestination(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id,
            @Valid @RequestBody EnableDestinationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
            "Destination added", connectionService.enableDestination(requireBinge(bingeId), id, request)));
    }

    /**
     * Verify a connection and put it live.
     *
     * <p>The step the console had no button for: every other transition existed, but
     * nothing could reach ACTIVE — which is what a reseller must authenticate against and
     * what a listing requires to publish.
     */
    @PostMapping("/connections/{id}/activate")
    public ResponseEntity<ApiResponse<ConnectionDto>> activate(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Connection activated",
            connectionService.activate(requireBinge(bingeId), id)));
    }

    /**
     * Issue (or rotate) the key a reseller presents to reach this venue.
     *
     * <p><b>The plaintext key is in this response and nowhere else, ever again.</b> Only
     * its digest is stored. The console must show it once and tell the operator to copy
     * it; a "show key" screen cannot exist, by construction.
     */
    @PostMapping("/connections/{id}/reseller-key")
    public ResponseEntity<ApiResponse<ConnectionService.IssuedResellerKey>> issueResellerKey(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id) {
        ConnectionService.IssuedResellerKey issued =
            connectionService.issueResellerKey(requireBinge(bingeId), id);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
            issued.replacedPrevious()
                ? "New key issued. The previous key stopped working immediately."
                : "Reseller key issued. Copy it now — it cannot be shown again.",
            issued));
    }

    @PostMapping("/connections/{id}/pause")
    public ResponseEntity<ApiResponse<ConnectionDto>> pause(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.ok("Connection paused",
            connectionService.pause(requireBinge(bingeId), id, reason)));
    }

    @PostMapping("/connections/{id}/resume")
    public ResponseEntity<ApiResponse<ConnectionDto>> resume(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Connection resumed",
            connectionService.resume(requireBinge(bingeId), id)));
    }

    @PostMapping("/connections/{id}/revoke")
    public ResponseEntity<ApiResponse<ConnectionDto>> revoke(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.ok("Connection revoked",
            connectionService.revoke(requireBinge(bingeId), id, reason)));
    }
}
