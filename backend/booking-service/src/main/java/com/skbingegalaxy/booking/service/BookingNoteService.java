package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.BookingNoteDto;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingNote;
import com.skbingegalaxy.booking.entity.BookingNote.Visibility;
import com.skbingegalaxy.booking.repository.BookingNoteRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Threaded support-note service for the operator console (Item 24).
 *
 * <p>The legacy {@code Booking.adminNotes} TEXT column is preserved on the
 * entity for read-back compatibility (older code paths still write to it),
 * but the canonical store going forward is {@link BookingNote}. The list
 * endpoint exposed via {@link com.skbingegalaxy.booking.controller.AdminBookingController#getBookingNotes}
 * returns this richer thread; the UI renders it as a timeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingNoteService {

    private final BookingNoteRepository noteRepository;
    private final BookingRepository bookingRepository;
    private final AdminBingeScopeService adminBingeScopeService;

    @Transactional(readOnly = true)
    public List<BookingNoteDto> list(String bookingRef, Long adminId, String role) {
        Booking b = requireBookingForRole(bookingRef, adminId, role, "viewing notes");
        return noteRepository.findByBookingRefAndDeletedFalseOrderByPinnedDescCreatedAtDesc(b.getBookingRef())
            .stream().map(BookingNoteDto::from).toList();
    }

    @Transactional
    public BookingNoteDto add(String bookingRef, String body, Visibility visibility, boolean pinned,
                              Long adminId, String adminName, String role) {
        Booking b = requireBookingForRole(bookingRef, adminId, role, "adding a note");
        if (body == null || body.isBlank()) {
            throw new BusinessException("Note body is required");
        }
        if (body.length() > 5000) {
            throw new BusinessException("Note body exceeds 5000 character limit");
        }
        BookingNote n = BookingNote.builder()
            .bookingRef(b.getBookingRef())
            .bingeId(b.getBingeId())
            .authorAdminId(adminId)
            .authorName(adminName != null && !adminName.isBlank() ? adminName : ("Admin#" + adminId))
            .body(body.trim())
            .visibility(visibility != null ? visibility : Visibility.INTERNAL)
            .pinned(pinned)
            .build();
        BookingNote saved = noteRepository.save(n);
        log.info("booking-note added bookingRef={} adminId={} visibility={} pinned={}",
            bookingRef, adminId, saved.getVisibility(), saved.isPinned());
        return BookingNoteDto.from(saved);
    }

    @Transactional
    public BookingNoteDto edit(Long noteId, String body, Long adminId, String role) {
        BookingNote n = noteRepository.findById(noteId)
            .orElseThrow(() -> new ResourceNotFoundException("BookingNote", "id", noteId));
        if (n.isDeleted()) throw new BusinessException("Note has been deleted");
        // Tenant guard: caller must own the note's binge before any author/window check
        // (defends against cross-tenant id enumeration on /notes/{id}).
        adminBingeScopeService.requireBingeOwnership(n.getBingeId(), adminId, role, "editing a note");
        // Only the original author can edit, and only within the edit window.
        // Super-admin override is intentionally omitted: tampering with another
        // operator's note breaks audit trust. Super-admin can soft-delete + add new.
        if (!n.getAuthorAdminId().equals(adminId)) {
            throw new BusinessException("Only the note's author can edit it");
        }
        Duration age = Duration.between(n.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC));
        if (age.toMinutes() > BookingNote.EDIT_WINDOW_MINUTES) {
            throw new BusinessException("Edit window of " + BookingNote.EDIT_WINDOW_MINUTES
                + " minutes has passed; please add a new note instead");
        }
        if (body == null || body.isBlank()) {
            throw new BusinessException("Note body is required");
        }
        n.setBody(body.trim());
        n.setEdited(true);
        return BookingNoteDto.from(noteRepository.save(n));
    }

    @Transactional
    public void softDelete(Long noteId, Long adminId, String role) {
        BookingNote n = noteRepository.findById(noteId)
            .orElseThrow(() -> new ResourceNotFoundException("BookingNote", "id", noteId));
        adminBingeScopeService.requireBingeOwnership(n.getBingeId(), adminId, role, "deleting a note");
        boolean isAuthor = n.getAuthorAdminId().equals(adminId);
        boolean isSuper = "SUPER_ADMIN".equalsIgnoreCase(role);
        if (!isAuthor && !isSuper) {
            throw new BusinessException("Only the author or a super-admin can delete a note");
        }
        n.setDeleted(true);
        noteRepository.save(n);
        log.info("booking-note soft-deleted id={} bookingRef={} byAdmin={}",
            noteId, n.getBookingRef(), adminId);
    }

    @Transactional
    public BookingNoteDto pin(Long noteId, boolean pinned, Long adminId, String role) {
        BookingNote n = noteRepository.findById(noteId)
            .orElseThrow(() -> new ResourceNotFoundException("BookingNote", "id", noteId));
        // Pinning is a curatorial action — anyone with admin access to the binge can do it.
        adminBingeScopeService.requireBingeOwnership(n.getBingeId(), adminId, role, "pinning a note");
        n.setPinned(pinned);
        return BookingNoteDto.from(noteRepository.save(n));
    }

    private Booking requireBookingForRole(String bookingRef, Long adminId, String role, String action) {
        Booking b = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));
        adminBingeScopeService.requireBingeOwnership(b.getBingeId(), adminId, role, action);
        return b;
    }
}
