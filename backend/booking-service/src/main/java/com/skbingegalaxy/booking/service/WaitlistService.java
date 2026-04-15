package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.entity.WaitlistEntry;
import com.skbingegalaxy.booking.entity.WaitlistEntry.WaitlistStatus;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitlistService {

    private final WaitlistRepository waitlistRepository;
    private final EventTypeRepository eventTypeRepository;
    private final BookingService bookingService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.waitlist.offer-expiry-minutes:30}")
    private int offerExpiryMinutes;

    @Value("${app.waitlist.max-per-customer:3}")
    private int maxPerCustomer;

    // ── Join waitlist ────────────────────────────────────────
    @Transactional
    public WaitlistEntryDto joinWaitlist(JoinWaitlistRequest request,
                                          Long customerId, String customerName,
                                          String customerEmail, String customerPhone) {
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
            .eventType(eventType)
            .preferredDate(request.getPreferredDate())
            .preferredStartTime(request.getPreferredStartTime())
            .durationMinutes(request.getDurationMinutes())
            .numberOfGuests(request.getNumberOfGuests())
            .status(WaitlistStatus.WAITING)
            .position(nextPos)
            .build();

        entry = waitlistRepository.save(entry);
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
    @Transactional(readOnly = true)
    public List<WaitlistEntryDto> getWaitlistForDate(LocalDate date) {
        Long bingeId = BingeContext.requireBingeId();
        return waitlistRepository
            .findByBingeIdAndPreferredDateAndStatusOrderByPositionAsc(bingeId, date, WaitlistStatus.WAITING)
            .stream().map(this::toDto).toList();
    }

    // ── Admin: waitlist count for a date ────────────────────
    @Transactional(readOnly = true)
    public long getWaitlistCount(LocalDate date) {
        Long bingeId = BingeContext.requireBingeId();
        return waitlistRepository.countByBingeIdAndPreferredDateAndStatus(bingeId, date, WaitlistStatus.WAITING);
    }

    // ── Promote: called when a booking is cancelled, check if waitlisted customers can be notified ──
    @Transactional
    public void promoteWaitlistOnCancellation(Long bingeId, LocalDate date) {
        List<WaitlistEntry> waiting = waitlistRepository
            .findByBingeIdAndPreferredDateAndStatusOrderByPositionAsc(bingeId, date, WaitlistStatus.WAITING);

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
                // Offer the slot to this customer
                entry.setStatus(WaitlistStatus.OFFERED);
                entry.setNotifiedAt(LocalDateTime.now());
                entry.setOfferExpiresAt(LocalDateTime.now().plusMinutes(offerExpiryMinutes));
                waitlistRepository.save(entry);

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

    // ── Expire stale offers (called by scheduler) ───────────
    @Transactional
    public int expireStaleOffers() {
        List<WaitlistEntry> expired = waitlistRepository
            .findByStatusAndOfferExpiresAtBefore(WaitlistStatus.OFFERED, LocalDateTime.now());
        for (WaitlistEntry entry : expired) {
            entry.setStatus(WaitlistStatus.EXPIRED);
            waitlistRepository.save(entry);
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
            kafkaTemplate.send(KafkaTopics.NOTIFICATION_SEND, event);
        } catch (Exception e) {
            log.error("Failed to send waitlist notification for entry {}", entry.getId(), e);
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
            .offerExpiresAt(e.getOfferExpiresAt())
            .notifiedAt(e.getNotifiedAt())
            .convertedBookingRef(e.getConvertedBookingRef())
            .createdAt(e.getCreatedAt())
            .build();
    }
}
