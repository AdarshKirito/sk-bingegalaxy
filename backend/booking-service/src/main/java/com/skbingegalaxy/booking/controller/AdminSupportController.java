package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BookingDto;
import com.skbingegalaxy.booking.dto.BookingNoteDto;
import com.skbingegalaxy.booking.entity.BookingNote.Visibility;
import com.skbingegalaxy.booking.service.BookingNoteService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Support console endpoints (Item 24). Grouped under
 * {@code /api/v1/bookings/admin/support} so the existing
 * {@link AdminBookingController} stays focused on lifecycle, while
 * this controller owns the operator-care surface (notes, escalation,
 * goodwill, resend confirmation).
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/support")
@RequiredArgsConstructor
public class AdminSupportController {

    private final BookingService bookingService;
    private final BookingNoteService noteService;

    /**
     * Single-booking lookup so the support console can search by ref without
     * being limited to the today-only {@code /admin/search} endpoint. Returns
     * 404 if the booking doesn't exist or isn't owned by the caller's binge.
     */
    @GetMapping("/{bookingRef}")
    public ResponseEntity<ApiResponse<BookingDto>> getByRef(
            @PathVariable String bookingRef) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getByRef(bookingRef)));
    }

    // ── Threaded notes ────────────────────────────────────────────────────

    @GetMapping("/{bookingRef}/notes")
    public ResponseEntity<ApiResponse<List<BookingNoteDto>>> listNotes(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(noteService.list(bookingRef, adminId, role)));
    }

    @PostMapping("/{bookingRef}/notes")
    public ResponseEntity<ApiResponse<BookingNoteDto>> addNote(
            @PathVariable String bookingRef,
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader(value = "X-User-Name", required = false) String adminName,
            @RequestHeader("X-User-Role") String role) {
        if (body == null) throw new BusinessException("Note payload is required");
        String text = String.valueOf(body.getOrDefault("body", ""));
        String visRaw = String.valueOf(body.getOrDefault("visibility", "INTERNAL"));
        Visibility vis = Visibility.valueOf(visRaw.toUpperCase());
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        return ResponseEntity.ok(ApiResponse.ok("Note added",
            noteService.add(bookingRef, text, vis, pinned, adminId, adminName, role)));
    }

    @PatchMapping("/notes/{noteId}")
    public ResponseEntity<ApiResponse<BookingNoteDto>> editNote(
            @PathVariable Long noteId,
            @RequestBody Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok("Note updated",
            noteService.edit(noteId, body == null ? null : body.get("body"), adminId, role)));
    }

    @DeleteMapping("/notes/{noteId}")
    public ResponseEntity<ApiResponse<Void>> deleteNote(
            @PathVariable Long noteId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        noteService.softDelete(noteId, adminId, role);
        return ResponseEntity.ok(ApiResponse.ok("Note deleted", null));
    }

    @PostMapping("/notes/{noteId}/pin")
    public ResponseEntity<ApiResponse<BookingNoteDto>> pinNote(
            @PathVariable Long noteId,
            @RequestBody(required = false) Map<String, Boolean> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        boolean pinned = body == null || body.get("pinned") == null ? true : body.get("pinned");
        return ResponseEntity.ok(ApiResponse.ok(
            noteService.pin(noteId, pinned, adminId, role)));
    }

    // ── Resend confirmation ───────────────────────────────────────────────

    @PostMapping("/{bookingRef}/resend-confirmation")
    public ResponseEntity<ApiResponse<BookingDto>> resendConfirmation(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long adminId) {
        return ResponseEntity.ok(ApiResponse.ok("Confirmation re-sent",
            bookingService.resendConfirmation(bookingRef, adminId)));
    }

    // ── Escalation ────────────────────────────────────────────────────────

    @PostMapping("/{bookingRef}/escalate")
    public ResponseEntity<ApiResponse<BookingDto>> escalate(
            @PathVariable String bookingRef,
            @RequestBody Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId) {
        if (body == null || body.get("level") == null) {
            throw new BusinessException("level is required (NONE | L1 | L2 | L3)");
        }
        return ResponseEntity.ok(ApiResponse.ok("Escalation updated",
            bookingService.setEscalation(bookingRef, body.get("level"), body.get("reason"), adminId)));
    }

    // ── Goodwill ──────────────────────────────────────────────────────────

    @PostMapping("/{bookingRef}/goodwill")
    public ResponseEntity<ApiResponse<BookingDto>> goodwill(
            @PathVariable String bookingRef,
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-User-Id") Long adminId) {
        if (body == null || body.get("amount") == null) {
            throw new BusinessException("amount is required");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount")));
        } catch (NumberFormatException ex) {
            throw new BusinessException("amount must be a numeric value");
        }
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return ResponseEntity.ok(ApiResponse.ok("Goodwill issued",
            bookingService.issueGoodwill(bookingRef, amount, reason, adminId)));
    }
}
