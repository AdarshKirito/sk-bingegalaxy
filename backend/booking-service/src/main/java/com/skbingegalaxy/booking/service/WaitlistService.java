package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.entity.WaitlistEntry;
import com.skbingegalaxy.booking.entity.WaitlistEntry.WaitlistStatus;
import com.skbingegalaxy.booking.metrics.BookingFunnelMetrics;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.booking.repository.WaitlistRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitlistService {

    private final WaitlistRepository waitlistRepository;
    private final EventTypeRepository eventTypeRepository;
    private final BookingFunnelMetrics funnelMetrics;
    private final BookingService bookingService;
    // BookingRepository is injected solely for its advisory-lock helper so that
    // {@link #promoteWaitlistOnCancellation} serialises with concurrent
    // booking-creation attempts on the same (bingeId, date). Without this lock
    // two cancellations could both promote position 1 → double slot offer.
    private final com.skbingegalaxy.booking.repository.BookingRepository bookingRepository;
    // Use transactional outbox (not direct KafkaTemplate) to avoid the dual-write problem:
    // if the surrounding DB transaction rolls back we don't want a customer to be notified
    // of a slot they weren't actually offered, and if Kafka is down we still want the
    // notification to eventually fire once the broker comes back.
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final BookingEventPublisher bookingEventPublisher;
    // Used to verify the slot is not admin-blocked before issuing an offer
    // (Item 26 — out-of-order: cancellation arrives after admin block).
    private final com.skbingegalaxy.booking.client.AvailabilityClient availabilityClient;

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Value("${app.waitlist.offer-expiry-minutes:30}")
    private int offerExpiryMinutes;

    @Value("${app.waitlist.max-per-customer:3}")
    private int maxPerCustomer;

    // ── Join waitlist ────────────────────────────────────────
    /** Backward-compat overload (callers without phone country code). */
    public WaitlistEntryDto joinWaitlist(JoinWaitlistRequest request,
                                          Long customerId, String customerName,
                                          String customerEmail, String customerPhone) {
        return joinWaitlist(request, customerId, customerName, customerEmail, customerPhone, null);
    }

    @Transactional
    public WaitlistEntryDto joinWaitlist(JoinWaitlistRequest request,
                                          Long customerId, String customerName,
                                          String customerEmail, String customerPhone,
                                          String customerPhoneCountryCode) {
        Long bingeId = BingeContext.requireBingeId();

        // Validate event type
        EventType eventType = eventTypeRepository.findById(request.getEventTypeId())
            .filter(et -> et.isActive() && bingeId.equals(et.getBingeId()))
            .orElseThrow(() -> new BusinessException("Invalid or inactive event type"));

        // Check for duplicate waitlist entry
        boolean alreadyWaiting = waitlistRepository
            .existsByBingeIdAndCustomerIdAndPreferredDateAndPreferredStartTimeAndStatusIn(
                bingeId, customerId, request.getPreferredDate(), request.getPreferredStartTime(),
                List.of(WaitlistStatus.WAITING, WaitlistStatus.OFFERED));
        if (alreadyWaiting) {
            throw new BusinessException("You are already on the waitlist for this date and time");
        }

        // Limit active waitlist entries per customer
        long activeCount = waitlistRepository
            .findByCustomerIdAndStatusInOrderByCreatedAtDesc(customerId,
                List.of(WaitlistStatus.WAITING, WaitlistStatus.OFFERED)).size();
        if (activeCount >= maxPerCustomer) {
            throw new BusinessException("You can have at most " + maxPerCustomer
                + " active waitlist entries. Cancel an existing one first.");
        }

        // Assign position
        int nextPos = waitlistRepository.findMaxPosition(bingeId, request.getPreferredDate()) + 1;

        WaitlistEntry entry = WaitlistEntry.builder()
            .bingeId(bingeId)
            .customerId(customerId)
            .customerName(customerName)
            .customerEmail(customerEmail)
            .customerPhone(customerPhone)
            .customerPhoneCountryCode(customerPhoneCountryCode)
            .eventType(eventType)
            .preferredDate(request.getPreferredDate())
            .preferredStartTime(request.getPreferredStartTime())
            .durationMinutes(request.getDurationMinutes())
            .numberOfGuests(request.getNumberOfGuests())
            .status(WaitlistStatus.WAITING)
            .position(nextPos)
            .build();

        entry = waitlistRepository.save(entry);
        funnelMetrics.waitlistJoined();
        log.info("Customer {} joined waitlist (pos {}) for binge {} on {}", customerId, nextPos, bingeId, request.getPreferredDate());
        return toDto(entry);
    }

    // ── Leave waitlist ───────────────────────────────────────
    @Transactional
    public void leaveWaitlist(Long entryId, Long customerId) {
        WaitlistEntry entry = waitlistRepository.findByIdAndCustomerId(entryId, customerId)
            .orElseThrow(() -> new ResourceNotFoundException("WaitlistEntry", "id", entryId));

        if (entry.getStatus() == WaitlistStatus.BOOKED || entry.getStatus() == WaitlistStatus.CANCELLED) {
            throw new BusinessException("This waitlist entry is already " + entry.getStatus());
        }

        entry.setStatus(WaitlistStatus.CANCELLED);
        waitlistRepository.save(entry);
        log.info("Customer {} cancelled waitlist entry {}", customerId, entryId);
    }

    // ── My waitlist entries ──────────────────────────────────
    @Transactional(readOnly = true)
    public List<WaitlistEntryDto> getMyWaitlistEntries(Long customerId) {
        Long bingeId = BingeContext.getBingeId();
        List<WaitlistEntry> entries = bingeId != null
            ? waitlistRepository.findByBingeIdAndCustomerIdOrderByCreatedAtDesc(bingeId, customerId)
            : waitlistRepository.findByCustomerIdAndStatusInOrderByCreatedAtDesc(customerId,
                List.of(WaitlistStatus.WAITING, WaitlistStatus.OFFERED));
        return entries.stream().map(this::toDto).toList();
    }

    // ── Admin: view waitlist for a date ─────────────────────
    // Returns entries in ALL statuses (WAITING, OFFERED, BOOKED, EXPIRED, CANCELLED)
    // so admins can audit history and act on stale offers.
    @Transactional(readOnly = true)
    public List<WaitlistEntryDto> getWaitlistForDate(LocalDate date) {
        Long bingeId = BingeContext.requireBingeId();
        return waitlistRepository
            .findByBingeIdAndPreferredDateOrderByStatusAscPositionAsc(bingeId, date)
            .stream().map(this::toDto).toList();
    }

    // ── Admin: waitlist count for a date ────────────────────
    @Transactional(readOnly = true)
    public long getWaitlistCount(LocalDate date) {
        Long bingeId = BingeContext.requireBingeId();
        return waitlistRepository.countByBingeIdAndPreferredDateAndStatus(bingeId, date, WaitlistStatus.WAITING);
    }

    // ── Admin: cancel any waitlist entry ────────────────────
    @Transactional
    public WaitlistEntryDto adminCancelEntry(Long entryId, Long adminId) {
        Long bingeId = BingeContext.requireBingeId();
        WaitlistEntry entry = waitlistRepository.findById(entryId)
            .orElseThrow(() -> new ResourceNotFoundException("WaitlistEntry", "id", entryId));
        if (!entry.getBingeId().equals(bingeId)) {
            // Defence in depth: never let an admin at venue A touch venue B's waitlist.
            throw new BusinessException("Waitlist entry does not belong to the selected venue");
        }
        if (entry.getStatus() == WaitlistStatus.BOOKED) {
            throw new BusinessException("Cannot cancel a waitlist entry that already converted to a booking");
        }
        if (entry.getStatus() == WaitlistStatus.CANCELLED) {
            return toDto(entry);
        }
        entry.setStatus(WaitlistStatus.CANCELLED);
        waitlistRepository.save(entry);
        log.info("Admin {} cancelled waitlist entry {} (binge {})", adminId, entryId, bingeId);
        return toDto(entry);
    }

    // ── Admin: manually offer a waitlist slot to next/specific customer ──
    @Transactional
    public WaitlistEntryDto adminOfferEntry(Long entryId, Long adminId) {
        Long bingeId = BingeContext.requireBingeId();
        WaitlistEntry entry = waitlistRepository.findById(entryId)
            .orElseThrow(() -> new ResourceNotFoundException("WaitlistEntry", "id", entryId));
        if (!entry.getBingeId().equals(bingeId)) {
            throw new BusinessException("Waitlist entry does not belong to the selected venue");
        }
        if (entry.getStatus() != WaitlistStatus.WAITING) {
            throw new BusinessException("Only WAITING entries can be offered. Current: " + entry.getStatus());
        }
        entry.setStatus(WaitlistStatus.OFFERED);
        entry.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        entry.setOfferExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(offerExpiryMinutes));
        waitlistRepository.save(entry);
        sendWaitlistNotification(entry);
        log.info("Admin {} manually offered waitlist entry {} (binge {})", adminId, entryId, bingeId);
        return toDto(entry);
    }

    /**
     * Set priority boost on a waitlist entry. Higher value is offered first;
     * ties broken by FIFO position. Useful for VIP/loyalty escalation and
     * ops overrides. Only entries in WAITING status can be re-prioritised
     * (already-OFFERED entries are mid-flight and re-prioritisation would
     * skip the implicit FIFO contract the customer was promised).
     */
    @Transactional
    public WaitlistEntryDto adminSetPriority(Long entryId, int priority, Long adminId) {
        Long bingeId = BingeContext.requireBingeId();
        if (priority < 0 || priority > 1000) {
            throw new BusinessException("Priority must be between 0 and 1000");
        }
        WaitlistEntry entry = waitlistRepository.findById(entryId)
            .orElseThrow(() -> new ResourceNotFoundException("WaitlistEntry", "id", entryId));
        if (!entry.getBingeId().equals(bingeId)) {
            throw new BusinessException("Waitlist entry does not belong to the selected venue");
        }
        if (entry.getStatus() != WaitlistStatus.WAITING) {
            throw new BusinessException(
                "Only WAITING entries can be re-prioritised. Current: " + entry.getStatus());
        }
        int prevPriority = entry.getPriority();
        entry.setPriority(priority);
        waitlistRepository.save(entry);
        log.info("Admin {} set priority of waitlist entry {} from {} -> {} (binge {})",
            adminId, entryId, prevPriority, priority, bingeId);
        return toDto(entry);
    }

    // ── Promote: called when a booking is cancelled, check if waitlisted customers can be notified ──
    @Transactional
    public void promoteWaitlistOnCancellation(Long bingeId, LocalDate date) {
        // Serialise with concurrent booking-creation AND concurrent waitlist
        // promotions on the same (bingeId, date). Without this lock two
        // cancellations arriving in parallel could both read "position 1 is
        // waiting, slot has capacity" and both promote, resulting in two
        // OFFERED entries racing for the same slot.
        bookingRepository.acquireSlotLock(BookingService.slotLockKeyFor(bingeId, date));

        List<WaitlistEntry> waiting = waitlistRepository
            .findByBingeIdAndPreferredDateAndStatusOrderByPriorityDescPositionAsc(bingeId, date, WaitlistStatus.WAITING);

        if (waiting.isEmpty()) {
            return;
        }

        // For each waiting entry, check if their specific slot now has capacity
        for (WaitlistEntry entry : waiting) {
            int startMinute = entry.getPreferredStartTime().getHour() * 60
                            + entry.getPreferredStartTime().getMinute();
            Map<String, Object> capacity = bookingService.getSlotCapacityForBinge(
                entry.getBingeId(), entry.getPreferredDate(), startMinute, entry.getDurationMinutes());

            boolean isFull = (boolean) capacity.get("isFull");
            if (!isFull) {
                // Item 26 guard: between the cancellation that triggered this
                // promotion and now, an admin may have BLOCKED the slot via
                // availability-service. Capacity check above only counts
                // existing bookings, not blocks — re-validate against the
                // authoritative availability source before offering.
                Boolean stillAvailable = false;
                try {
                    stillAvailable = availabilityClient.checkSlotAvailable(
                        internalApiSecret,
                        entry.getPreferredDate(),
                        entry.getBingeId(),
                        startMinute,
                        entry.getDurationMinutes());
                } catch (Exception e) {
                    log.warn("Waitlist promotion: availability re-check failed for entry {} ({}); skipping",
                        entry.getId(), e.getMessage());
                    continue;
                }
                if (Boolean.FALSE.equals(stillAvailable)) {
                    log.info("Waitlist promotion: slot is admin-blocked or unavailable; skipping entry {} for {}",
                        entry.getId(), entry.getPreferredDate());
                    continue;
                }
                // Offer the slot to this customer
                entry.setStatus(WaitlistStatus.OFFERED);
                entry.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
                entry.setOfferExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(offerExpiryMinutes));
                waitlistRepository.save(entry);
                funnelMetrics.waitlistOffered();

                // Send notification
                sendWaitlistNotification(entry);
                log.info("Offered waitlist slot to customer {} (entry {}) for date {}",
                    entry.getCustomerId(), entry.getId(), date);
                break; // Offer to one customer at a time
            }
        }
    }

    /** Called from Kafka listener (no BingeContext). */
    @Transactional
    public void promoteWaitlistAfterCancellation(com.skbingegalaxy.common.event.BookingEvent event) {
        if (event.getBingeId() == null || event.getBookingDate() == null) return;
        promoteWaitlistOnCancellation(event.getBingeId(), event.getBookingDate());
    }

    /**
     * Marks a waitlist entry as BOOKED after the customer successfully converts
     * their offered slot into a confirmed booking. Call this from BookingService
     * (or a post-booking Kafka listener) after the booking saga completes.
     *
     * This is the missing transition that closes the funnel:
     *   OFFERED → BOOKED = waitlist converted; OFFERED → EXPIRED = offer abandoned.
     * The ratio of converted÷offered is the waitlist conversion rate tracked in Grafana.
     */
    @Transactional
    public void markEntryConverted(Long entryId, String bookingRef) {
        if (entryId == null) return;
        waitlistRepository.findById(entryId).ifPresent(entry -> {
            if (entry.getStatus() != WaitlistStatus.OFFERED) return;
            entry.setStatus(WaitlistStatus.BOOKED);
            entry.setConvertedBookingRef(bookingRef);
            waitlistRepository.save(entry);
            funnelMetrics.waitlistConverted();
            log.info("Waitlist entry {} converted to booking {} for customer {}",
                entryId, bookingRef, entry.getCustomerId());
        });
    }

    // ── Expire stale offers (called by scheduler) ───────────
    @Transactional
    public int expireStaleOffers() {
        List<WaitlistEntry> expired = waitlistRepository
            .findByStatusAndOfferExpiresAtBefore(WaitlistStatus.OFFERED, LocalDateTime.now(ZoneOffset.UTC));
        for (WaitlistEntry entry : expired) {
            entry.setStatus(WaitlistStatus.EXPIRED);
            waitlistRepository.save(entry);
            funnelMetrics.waitlistExpired();
            log.info("Waitlist offer expired for entry {} (customer {})", entry.getId(), entry.getCustomerId());

            // Try to promote next in queue
            promoteWaitlistOnCancellation(entry.getBingeId(), entry.getPreferredDate());
        }
        return expired.size();
    }

    private void sendWaitlistNotification(WaitlistEntry entry) {
        try {
            NotificationEvent event = NotificationEvent.builder()
                .type("WAITLIST_SLOT_AVAILABLE")
                .channel(NotificationChannel.EMAIL)
                .recipientEmail(entry.getCustomerEmail())
                .recipientName(entry.getCustomerName())
                .recipientPhone(entry.getCustomerPhone())
                .recipientPhoneCountryCode(entry.getCustomerPhoneCountryCode())
                .subject("A spot just opened up! - SK Binge Galaxy")
                .body(String.format(
                    "Great news, %s! A spot just opened up for %s on %s at %s. " +
                    "You have %d minutes to complete your booking before the offer expires. " +
                    "Log in to your account and book now!",
                    entry.getCustomerName(),
                    entry.getEventType().getName(),
                    entry.getPreferredDate(),
                    entry.getPreferredStartTime(),
                    offerExpiryMinutes))
                .metadata(Map.of(
                    "waitlistEntryId", String.valueOf(entry.getId()),
                    "eventType", entry.getEventType().getName(),
                    "date", entry.getPreferredDate().toString(),
                    "startTime", entry.getPreferredStartTime().toString()
                ))
                .build();
            // Use the central publisher: stamps eventId/version/correlationId
            // and persists in the same transaction. One key per waitlist entry
            // keeps re-offers in partition order.
            bookingEventPublisher.publish(KafkaTopics.NOTIFICATION_SEND, "WL-" + entry.getId(), event);

            // Also emit a dedicated waitlist.promoted domain event so downstream
            // consumers (analytics, external CRM) can react to the promotion
            // without parsing notification payloads. Same envelope, new topic.
            NotificationEvent promoted = NotificationEvent.builder()
                .type("WAITLIST_PROMOTED")
                .channel(NotificationChannel.EMAIL)
                .recipientEmail(entry.getCustomerEmail())
                .recipientName(entry.getCustomerName())
                .metadata(Map.of(
                    "waitlistEntryId", String.valueOf(entry.getId()),
                    "bingeId", String.valueOf(entry.getBingeId()),
                    "customerId", String.valueOf(entry.getCustomerId()),
                    "eventTypeName", entry.getEventType().getName(),
                    "preferredDate", entry.getPreferredDate().toString(),
                    "preferredStartTime", entry.getPreferredStartTime().toString()
                ))
                .build();
            bookingEventPublisher.publish(KafkaTopics.WAITLIST_PROMOTED, "WL-" + entry.getId(), promoted);
        } catch (Exception e) {
            // Publisher already wraps Jackson errors in IllegalStateException;
            // swallow & log so a failed offer-notification doesn't roll back the
            // promotion itself (the customer still has the slot held).
            log.error("Failed to enqueue waitlist notification outbox row for entry {}",
                entry.getId(), e);
        }
    }

    private WaitlistEntryDto toDto(WaitlistEntry e) {
        return WaitlistEntryDto.builder()
            .id(e.getId())
            .bingeId(e.getBingeId())
            .customerId(e.getCustomerId())
            .customerName(e.getCustomerName())
            .customerEmail(e.getCustomerEmail())
            .customerPhone(e.getCustomerPhone())
            .customerPhoneCountryCode(e.getCustomerPhoneCountryCode())
            .eventType(EventTypeDto.builder()
                .id(e.getEventType().getId())
                .name(e.getEventType().getName())
                .description(e.getEventType().getDescription())
                .build())
            .preferredDate(e.getPreferredDate())
            .preferredStartTime(e.getPreferredStartTime())
            .durationMinutes(e.getDurationMinutes())
            .numberOfGuests(e.getNumberOfGuests())
            .status(e.getStatus().name())
            .position(e.getPosition())
            .priority(e.getPriority())
            .offerExpiresAt(e.getOfferExpiresAt())
            .notifiedAt(e.getNotifiedAt())
            .convertedBookingRef(e.getConvertedBookingRef())
            .createdAt(e.getCreatedAt())
            .build();
    }
}
