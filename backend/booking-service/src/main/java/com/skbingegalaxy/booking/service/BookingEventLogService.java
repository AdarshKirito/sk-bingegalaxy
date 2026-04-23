package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingEventLog;
import com.skbingegalaxy.booking.entity.BookingEventType;
import com.skbingegalaxy.booking.repository.BookingEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records immutable event logs for every booking state change.
 * This provides an append-only audit trail used by the CQRS read-model
 * projection ({@link BookingProjectionService}) — NOT true Event Sourcing,
 * since the {@code Booking} entity table remains the source of truth.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingEventLogService {

    private final BookingEventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;
    private final BookingProjectionService projectionService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(Booking booking, BookingEventType eventType, String previousStatus,
                         Long triggeredBy, String triggeredByRole, String description) {
        logEvent(booking, eventType, previousStatus, triggeredBy, triggeredByRole, null, description);
    }

    /**
     * Extended logger that captures a human-readable actor name so the audit
     * trail can render "Aarav Menon (Customer)" instead of a bare user id.
     * The name is snapshotted for historical stability.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(Booking booking, BookingEventType eventType, String previousStatus,
                         Long triggeredBy, String triggeredByRole,
                         String triggeredByName, String description) {
        try {
            String resolvedName = resolveActorName(booking, triggeredByRole, triggeredByName);
            BookingEventLog event = BookingEventLog.builder()
                .bookingRef(booking.getBookingRef())
                .eventType(eventType)
                .previousStatus(previousStatus)
                .newStatus(booking.getStatus().name())
                .triggeredBy(triggeredBy)
                .triggeredByRole(triggeredByRole)
                .triggeredByName(resolvedName)
                .description(description)
                .snapshot(buildSnapshot(booking))
                .build();

            eventLogRepository.save(event);
            projectionService.applyEvent(event);
            log.debug("Event logged: {} for booking {}", eventType, booking.getBookingRef());
        } catch (Exception e) {
            // Event logging must never break the main flow
            log.warn("Failed to log booking event {} for {}: {}",
                     eventType, booking.getBookingRef(), e.getMessage());
        }
    }

    /**
     * Resolve a best-effort display name for the actor.
     *  • CUSTOMER → falls back to the name stored on the booking.
     *  • ADMIN / SUPER_ADMIN → uses supplied name, else the role label.
     *  • SYSTEM / null → "System" (automated workflows / schedulers).
     */
    private String resolveActorName(Booking booking, String role, String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) {
            return explicitName.trim();
        }
        if (role == null || role.isBlank() || "SYSTEM".equalsIgnoreCase(role)) {
            return "System";
        }
        if ("CUSTOMER".equalsIgnoreCase(role) && booking.getCustomerName() != null
                && !booking.getCustomerName().isBlank()) {
            return booking.getCustomerName().trim();
        }
        if ("SUPER_ADMIN".equalsIgnoreCase(role)) return "Super Admin";
        if ("ADMIN".equalsIgnoreCase(role)) return "Admin";
        return null;
    }

    public List<BookingEventLog> getEventHistory(String bookingRef) {
        return eventLogRepository.findByBookingRefOrderByCreatedAtAsc(bookingRef);
    }

    public Page<BookingEventLog> getEventHistory(String bookingRef, Pageable pageable) {
        return eventLogRepository.findByBookingRefOrderByCreatedAtAsc(bookingRef, pageable);
    }

    private String buildSnapshot(Booking booking) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("status", booking.getStatus().name());
            snap.put("paymentStatus", booking.getPaymentStatus().name());
            snap.put("totalAmount", booking.getTotalAmount());
            snap.put("collectedAmount", booking.getCollectedAmount());
            snap.put("bookingDate", booking.getBookingDate().toString());
            snap.put("startTime", booking.getStartTime().toString());
            snap.put("durationMinutes", booking.getDurationMinutes());
            snap.put("numberOfGuests", booking.getNumberOfGuests());
            snap.put("checkedIn", booking.isCheckedIn());
            snap.put("customerId", booking.getCustomerId());
            snap.put("eventTypeId", booking.getEventType().getId());
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            return "{}";
        }
    }
}
