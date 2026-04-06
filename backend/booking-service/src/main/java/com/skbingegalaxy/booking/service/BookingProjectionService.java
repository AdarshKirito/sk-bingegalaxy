package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.entity.BookingEventLog;
import com.skbingegalaxy.booking.entity.BookingReadModel;
import com.skbingegalaxy.booking.repository.BookingEventLogRepository;
import com.skbingegalaxy.booking.repository.BookingReadModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Maintains the CQRS read model by applying event snapshots.
 * Supports full replay from the event log for consistency checks or schema migrations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingProjectionService {

    private final BookingEventLogRepository eventLogRepository;
    private final BookingReadModelRepository readModelRepository;
    private final ObjectMapper objectMapper;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * Apply a single event to the read model (called inline after event logging).
     */
    @Transactional
    public void applyEvent(BookingEventLog event) {
        try {
            BookingReadModel model = readModelRepository.findByBookingRef(event.getBookingRef())
                .orElseGet(() -> BookingReadModel.builder()
                    .bookingRef(event.getBookingRef())
                    .eventCount(0)
                    .build());

            applySnapshot(model, event);

            model.setEventCount(model.getEventCount() + 1);
            model.setLastEventId(event.getId());
            readModelRepository.save(model);
        } catch (Exception e) {
            log.warn("Failed to project event {} for booking {}: {}",
                     event.getId(), event.getBookingRef(), e.getMessage());
        }
    }

    /**
     * Replay all events for a single booking — rebuilds the read model from scratch.
     */
    @Transactional
    public void replayBooking(String bookingRef) {
        readModelRepository.deleteByBookingRef(bookingRef);

        List<BookingEventLog> events = eventLogRepository
            .findByBookingRefOrderByCreatedAtAsc(bookingRef);

        if (events.isEmpty()) {
            log.info("No events to replay for booking {}", bookingRef);
            return;
        }

        BookingReadModel model = BookingReadModel.builder()
            .bookingRef(bookingRef)
            .eventCount(0)
            .build();

        for (BookingEventLog event : events) {
            applySnapshot(model, event);
            model.setEventCount(model.getEventCount() + 1);
            model.setLastEventId(event.getId());
        }

        readModelRepository.save(model);
        log.info("Replayed {} events for booking {}", events.size(), bookingRef);
    }

    /**
     * Full replay of all bookings — useful after schema migration or bug fix.
     * Returns the number of bookings replayed.
     */
    @Transactional
    public int replayAll() {
        readModelRepository.deleteAll();

        List<BookingEventLog> allEvents = eventLogRepository.findAll();

        // Group events by bookingRef, preserving order
        Map<String, List<BookingEventLog>> grouped = new java.util.LinkedHashMap<>();
        for (BookingEventLog event : allEvents) {
            grouped.computeIfAbsent(event.getBookingRef(), k -> new java.util.ArrayList<>()).add(event);
        }

        for (Map.Entry<String, List<BookingEventLog>> entry : grouped.entrySet()) {
            BookingReadModel model = BookingReadModel.builder()
                .bookingRef(entry.getKey())
                .eventCount(0)
                .build();

            for (BookingEventLog event : entry.getValue()) {
                applySnapshot(model, event);
                model.setEventCount(model.getEventCount() + 1);
                model.setLastEventId(event.getId());
            }

            readModelRepository.save(model);
        }

        log.info("Full replay complete: {} bookings projected", grouped.size());
        return grouped.size();
    }

    private void applySnapshot(BookingReadModel model, BookingEventLog event) {
        model.setStatus(event.getNewStatus());

        if (event.getSnapshot() == null || event.getSnapshot().isBlank()) {
            return;
        }

        try {
            Map<String, Object> snap = objectMapper.readValue(event.getSnapshot(), MAP_TYPE);

            if (snap.containsKey("paymentStatus")) {
                model.setPaymentStatus(String.valueOf(snap.get("paymentStatus")));
            }
            if (snap.containsKey("totalAmount")) {
                model.setTotalAmount(new BigDecimal(String.valueOf(snap.get("totalAmount"))));
            }
            if (snap.containsKey("collectedAmount") && snap.get("collectedAmount") != null) {
                model.setCollectedAmount(new BigDecimal(String.valueOf(snap.get("collectedAmount"))));
            }
            if (snap.containsKey("bookingDate")) {
                model.setBookingDate(LocalDate.parse(String.valueOf(snap.get("bookingDate"))));
            }
            if (snap.containsKey("startTime")) {
                model.setStartTime(LocalTime.parse(String.valueOf(snap.get("startTime"))));
            }
            if (snap.containsKey("durationMinutes")) {
                model.setDurationMinutes(((Number) snap.get("durationMinutes")).intValue());
            }
            if (snap.containsKey("numberOfGuests")) {
                model.setNumberOfGuests(((Number) snap.get("numberOfGuests")).intValue());
            }
            if (snap.containsKey("checkedIn")) {
                model.setCheckedIn(Boolean.parseBoolean(String.valueOf(snap.get("checkedIn"))));
            }
            if (snap.containsKey("customerId")) {
                model.setCustomerId(((Number) snap.get("customerId")).longValue());
            }
            if (snap.containsKey("eventTypeId")) {
                model.setEventTypeId(((Number) snap.get("eventTypeId")).longValue());
            }
        } catch (Exception e) {
            log.warn("Failed to parse snapshot for event {}: {}", event.getId(), e.getMessage());
        }
    }
}
