package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.CreateSlotHoldRequest;
import com.skbingegalaxy.booking.dto.SlotHoldDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.SlotHoldService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Pre-payment slot holds. Customers acquire a hold when they reach checkout
 * and release / convert it from there. Admins can list active holds (live
 * "active reservations" view) and force-release them when needed.
 */
@RestController
@RequestMapping("/api/v1/bookings/slot-holds")
@RequiredArgsConstructor
public class SlotHoldController {

    private final SlotHoldService slotHoldService;
    private final BookingService bookingService;
    private final AdminBingeScopeService adminBingeScopeService;

    @ModelAttribute
    void validateSelectedBinge() {
        adminBingeScopeService.requireSelectedBinge("accessing slot holds");
    }

    // ── Customer ─────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<SlotHoldDto>> createHold(
            @Valid @RequestBody CreateSlotHoldRequest request,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @RequestHeader(value = "X-User-Name", defaultValue = "Customer") String name) {
        SlotHoldDto dto = slotHoldService.createHold(
            request, userId, name, email,
            // Re-use the booking-service slot-availability rules so holds and
            // bookings see exactly the same world (no drift between the two).
            (bingeId, date, startMinute, durationMinutes, venueRoomId, excludeHoldToken) ->
                bookingService.assertSlotAvailableForHold(
                    bingeId, date, startMinute, durationMinutes, venueRoomId, excludeHoldToken));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Slot held — please complete payment within the timer", dto));
    }

    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<SlotHoldDto>> getHold(
            @PathVariable String token,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "CUSTOMER") String role) {
        boolean admin = "ADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role);
        return ResponseEntity.ok(ApiResponse.ok(slotHoldService.getByToken(token, userId, admin)));
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<ApiResponse<SlotHoldDto>> releaseHold(
            @PathVariable String token,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "CUSTOMER") String role,
            @RequestParam(value = "reason", required = false) String reason) {
        boolean admin = "ADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role);
        SlotHoldDto dto = slotHoldService.releaseByToken(token, userId, admin, reason);
        return ResponseEntity.ok(ApiResponse.ok("Slot hold released", dto));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<SlotHoldDto>>> myHolds(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(slotHoldService.listMyLiveHolds(userId)));
    }

    // ── Admin ────────────────────────────────────────────────────────────

    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<List<SlotHoldDto>>> listActive(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireManagedBinge(adminId, role, "viewing active slot holds");
        return ResponseEntity.ok(ApiResponse.ok(slotHoldService.listActiveHoldsForCurrentBinge()));
    }

    @DeleteMapping("/admin/{token}")
    public ResponseEntity<ApiResponse<SlotHoldDto>> adminRelease(
            @PathVariable String token,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(value = "reason", required = false) String reason) {
        adminBingeScopeService.requireManagedBinge(adminId, role, "releasing slot holds");
        SlotHoldDto dto = slotHoldService.releaseByToken(token, adminId, true,
            reason != null ? reason : "ADMIN_FORCED_RELEASE");
        return ResponseEntity.ok(ApiResponse.ok("Slot hold released by admin", dto));
    }
}
