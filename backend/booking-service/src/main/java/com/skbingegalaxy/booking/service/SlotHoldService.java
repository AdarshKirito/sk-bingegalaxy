package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CreateSlotHoldRequest;
import com.skbingegalaxy.booking.dto.SlotHoldDto;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.entity.SlotHold;
import com.skbingegalaxy.booking.entity.SlotHold.SlotHoldStatus;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.SlotHoldRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages temporary pre-payment slot holds. Holds participate in the same
 * conflict / capacity checks that the booking creation flow uses, so two
 * customers cannot independently take the same slot during checkout.
 *
 * <p>Concurrency strategy:
 * <ul>
 *   <li>Hold creation acquires the same per-(binge, date) PostgreSQL advisory
 *       lock that {@code BookingService} uses, eliminating the race where two
 *       customers grab a hold for an empty slot at the same time.</li>
 *   <li>Hold consumption / release is guarded by an optimistic version on
 *       {@link SlotHold}. A double-consume races degrade to a clean exception
 *       instead of a duplicate booking.</li>
 *   <li>Expiry is run by a single ShedLock-protected scheduler. Customers can
 *       always release a hold themselves; the scheduler only catches abandoned
 *       sessions.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlotHoldService {

    private final SlotHoldRepository slotHoldRepository;
    private final EventTypeRepository eventTypeRepository;
    /** Used purely for its advisory-lock helper so hold creation serialises with bookings. */
    private final com.skbingegalaxy.booking.repository.BookingRepository bookingRepository;

    @Value("${app.slot-hold.ttl-minutes:7}")
    private int holdTtlMinutes;

    @Value("${app.slot-hold.max-active-per-customer:1}")
    private int maxActivePerCustomer;

    @Value("${app.slot-hold.terminal-retention-days:7}")
    private int terminalRetentionDays;

    // ───────────────────────────────────────────────────────────────────────
    // Customer-facing API
    // ───────────────────────────────────────────────────────────────────────

    @Transactional
    public SlotHoldDto createHold(CreateSlotHoldRequest req,
                                   Long customerId,
                                   String customerName,
                                   String customerEmail,
                                   ConflictChecker conflictChecker) {
        Long bingeId = BingeContext.requireBingeId();

        if (req.getDurationMinutes() <= 0 || req.getDurationMinutes() % 30 != 0) {
            throw new BusinessException("Duration must be a positive 30-minute multiple");
        }
        if (req.getBookingDate().isBefore(LocalDate.now())) {
            throw new BusinessException("Cannot hold a slot in the past");
        }

        EventType eventType = eventTypeRepository.findById(req.getEventTypeId())
            .filter(et -> et.isActive() && bingeId.equals(et.getBingeId()))
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", req.getEventTypeId()));

        // Serialise with concurrent booking creates / other holds on this slot.
        bookingRepository.acquireSlotLock(slotLockKey(bingeId, req.getBookingDate()));

        LocalDateTime now = LocalDateTime.now();

        // Anti-abuse: cap concurrent active holds per customer per binge so a
        // user can't hold the entire schedule open and starve other customers.
        long live = slotHoldRepository.countLiveByCustomer(customerId, bingeId, now);
        if (live >= maxActivePerCustomer) {
            throw new BusinessException(
                "You already have an active slot hold. Complete or release it before starting another.");
        }

        int startMinute = req.getStartTime().getHour() * 60 + req.getStartTime().getMinute();

        // Defer to BookingService for the actual conflict / capacity rules. This
        // keeps a single source of truth and guarantees holds and bookings see
        // exactly the same world.
        conflictChecker.assertSlotAvailable(bingeId, req.getBookingDate(), startMinute,
            req.getDurationMinutes(), req.getVenueRoomId(), null /* excludeToken */);

        SlotHold hold = SlotHold.builder()
            .holdToken(generateToken())
            .bingeId(bingeId)
            .customerId(customerId)
            .customerName(customerName)
            .customerEmail(customerEmail)
            .eventType(eventType)
            .bookingDate(req.getBookingDate())
            .startTime(req.getStartTime())
            .durationMinutes(req.getDurationMinutes())
            .numberOfGuests(Math.max(1, req.getNumberOfGuests()))
            .venueRoomId(req.getVenueRoomId())
            .status(SlotHoldStatus.ACTIVE)
            .expiresAt(now.plusMinutes(holdTtlMinutes))
            .build();

        SlotHold saved = slotHoldRepository.save(hold);
        log.info("Slot hold created: token={} customer={} binge={} {} {}+{}m (expires {})",
            saved.getHoldToken(), customerId, bingeId, saved.getBookingDate(),
            saved.getStartTime(), saved.getDurationMinutes(), saved.getExpiresAt());
        return toDto(saved, now);
    }

    @Transactional(readOnly = true)
    public SlotHoldDto getByToken(String token, Long customerId, boolean adminAccess) {
        SlotHold hold = slotHoldRepository.findByHoldToken(token)
            .orElseThrow(() -> new ResourceNotFoundException("SlotHold", "token", token));
        if (!adminAccess && !Objects.equals(hold.getCustomerId(), customerId)) {
            throw new BusinessException("Not authorised to view this hold");
        }
        return toDto(hold, LocalDateTime.now());
    }

    @Transactional
    public SlotHoldDto releaseByToken(String token, Long customerId, boolean adminAccess, String reason) {
        SlotHold hold = slotHoldRepository.findByHoldToken(token)
            .orElseThrow(() -> new ResourceNotFoundException("SlotHold", "token", token));
        if (!adminAccess && !Objects.equals(hold.getCustomerId(), customerId)) {
            throw new BusinessException("Not authorised to release this hold");
        }
        if (hold.getStatus() != SlotHoldStatus.ACTIVE) {
            // Idempotent: releasing an already-terminal hold returns its current state.
            return toDto(hold, LocalDateTime.now());
        }
        hold.setStatus(SlotHoldStatus.RELEASED);
        hold.setReleasedAt(LocalDateTime.now());
        hold.setReleaseReason(reason != null ? reason : (adminAccess ? "ADMIN_RELEASE" : "CUSTOMER_RELEASE"));
        try {
            SlotHold saved = slotHoldRepository.save(hold);
            log.info("Slot hold released: token={} reason={}", saved.getHoldToken(), saved.getReleaseReason());
            return toDto(saved, LocalDateTime.now());
        } catch (OptimisticLockingFailureException ex) {
            log.warn("Slot hold release race for token={}; refetching", token);
            return getByToken(token, customerId, adminAccess);
        }
    }

    @Transactional(readOnly = true)
    public List<SlotHoldDto> listMyLiveHolds(Long customerId) {
        LocalDateTime now = LocalDateTime.now();
        return slotHoldRepository.findLiveByCustomer(customerId, now).stream()
            .map(h -> toDto(h, now))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SlotHoldDto> listActiveHoldsForCurrentBinge() {
        Long bingeId = BingeContext.requireBingeId();
        LocalDateTime now = LocalDateTime.now();
        return slotHoldRepository
            .findByBingeIdAndStatusOrderByExpiresAtAsc(bingeId, SlotHoldStatus.ACTIVE)
            .stream()
            // Filter expired-but-not-yet-cleaned-up rows out of the admin view.
            .filter(h -> h.getExpiresAt() != null && h.getExpiresAt().isAfter(now))
            .map(h -> toDto(h, now))
            .collect(Collectors.toList());
    }

    // ───────────────────────────────────────────────────────────────────────
    // Internal API used by BookingService
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Validates a hold matches the booking request and atomically marks it CONVERTED
     * with the new booking ref. Called from inside the booking-create transaction
     * so that a failed booking save naturally rolls back the consume.
     *
     * @throws BusinessException if the hold is missing, expired, owned by another
     *                           customer, or doesn't match the booking inputs.
     */
    @Transactional
    public SlotHold consumeHold(String token, Long customerId, Long eventTypeId,
                                LocalDate bookingDate, LocalTime startTime,
                                int durationMinutes, String bookingRef) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("Hold token is required to confirm this booking");
        }
        SlotHold hold = slotHoldRepository.findByHoldToken(token)
            .orElseThrow(() -> new BusinessException("Your slot hold is no longer valid. Please re-select your slot."));

        if (!Objects.equals(hold.getCustomerId(), customerId)) {
            throw new BusinessException("Slot hold does not belong to this customer.");
        }
        if (hold.getStatus() != SlotHoldStatus.ACTIVE) {
            throw new BusinessException("Slot hold has already been " + hold.getStatus().name().toLowerCase()
                + ". Please re-select your slot.");
        }
        if (hold.getExpiresAt() == null || hold.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("Your slot hold has expired. Please re-select your slot.");
        }

        // Defence in depth: the booking inputs must match the hold inputs exactly,
        // otherwise a malicious / buggy client could acquire a hold for slot A
        // and then submit a booking for slot B "wrapped" in the same token.
        if (!Objects.equals(hold.getEventType().getId(), eventTypeId)
            || !Objects.equals(hold.getBookingDate(), bookingDate)
            || !Objects.equals(hold.getStartTime(), startTime)
            || hold.getDurationMinutes() != durationMinutes) {
            throw new BusinessException("Booking details do not match your slot hold. Please re-select your slot.");
        }

        hold.setStatus(SlotHoldStatus.CONVERTED);
        hold.setConvertedBookingRef(bookingRef);
        try {
            return slotHoldRepository.save(hold);
        } catch (OptimisticLockingFailureException ex) {
            throw new BusinessException("Your slot hold was modified concurrently. Please re-select your slot.");
        }
    }

    /** Returns counts of overlapping live holds for capacity / conflict checks. */
    @Transactional(readOnly = true)
    public List<SlotHold> findLiveHoldsForSlot(Long bingeId, LocalDate date) {
        return slotHoldRepository.findLiveHoldsByBingeAndDate(bingeId, date, LocalDateTime.now());
    }

    /** Marks a hold RELEASED non-throwing — for use when payment downstream fails. */
    @Transactional
    public void releaseQuietly(String token, String reason) {
        if (token == null || token.isBlank()) return;
        slotHoldRepository.findByHoldToken(token).ifPresent(h -> {
            if (h.getStatus() != SlotHoldStatus.ACTIVE) return;
            h.setStatus(SlotHoldStatus.RELEASED);
            h.setReleasedAt(LocalDateTime.now());
            h.setReleaseReason(reason != null ? reason : "PAYMENT_FAILED");
            try {
                slotHoldRepository.save(h);
                log.info("Slot hold released quietly: token={} reason={}", token, reason);
            } catch (OptimisticLockingFailureException ignored) {
                // Race with the expiry scheduler — fine.
            }
        });
    }

    // ───────────────────────────────────────────────────────────────────────
    // Scheduler-facing API
    // ───────────────────────────────────────────────────────────────────────

    @Transactional
    public int expireStaleHolds() {
        LocalDateTime now = LocalDateTime.now();
        List<SlotHold> stale = slotHoldRepository.findExpiredActiveHolds(now);
        if (stale.isEmpty()) return 0;
        int n = 0;
        for (SlotHold h : stale) {
            try {
                h.setStatus(SlotHoldStatus.EXPIRED);
                h.setReleasedAt(now);
                h.setReleaseReason("TTL_EXPIRED");
                slotHoldRepository.save(h);
                n++;
            } catch (OptimisticLockingFailureException ignored) {
                // Customer / payment flow updated it concurrently — skip.
            }
        }
        log.info("Slot-hold scheduler: expired {} stale holds", n);
        return n;
    }

    @Transactional
    public int purgeOldTerminalHolds() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, terminalRetentionDays));
        int deleted = slotHoldRepository.deleteOldTerminalHolds(cutoff);
        if (deleted > 0) log.info("Slot-hold scheduler: purged {} old terminal holds (>{}d)", deleted, terminalRetentionDays);
        return deleted;
    }

    // ───────────────────────────────────────────────────────────────────────
    // Internals
    // ───────────────────────────────────────────────────────────────────────

    private static String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long slotLockKey(Long bingeId, LocalDate date) {
        // Mirror BookingService.slotLockKey — same key produces the same advisory
        // lock so holds and bookings serialise on the same critical section.
        return BookingService.slotLockKeyFor(bingeId, date);
    }

    private SlotHoldDto toDto(SlotHold h, LocalDateTime now) {
        long secondsRemaining = h.getStatus() == SlotHoldStatus.ACTIVE && h.getExpiresAt() != null
            ? Math.max(0, ChronoUnit.SECONDS.between(now, h.getExpiresAt()))
            : 0L;
        return SlotHoldDto.builder()
            .id(h.getId())
            .holdToken(h.getHoldToken())
            .bingeId(h.getBingeId())
            .customerId(h.getCustomerId())
            .customerName(h.getCustomerName())
            .customerEmail(h.getCustomerEmail())
            .eventTypeId(h.getEventType() != null ? h.getEventType().getId() : null)
            .eventTypeName(h.getEventType() != null ? h.getEventType().getName() : null)
            .bookingDate(h.getBookingDate())
            .startTime(h.getStartTime())
            .durationMinutes(h.getDurationMinutes())
            .numberOfGuests(h.getNumberOfGuests())
            .venueRoomId(h.getVenueRoomId())
            .status(h.getStatus().name())
            .expiresAt(h.getExpiresAt())
            .secondsRemaining(secondsRemaining)
            .convertedBookingRef(h.getConvertedBookingRef())
            .releaseReason(h.getReleaseReason())
            .createdAt(h.getCreatedAt())
            .build();
    }

    /**
     * Strategy injected by {@link BookingService} so this service does not need
     * a direct compile-time dependency on the booking conflict / capacity code.
     * Implementation throws {@link BusinessException} when the slot is not
     * available (mirrors createBooking's checks).
     */
    @FunctionalInterface
    public interface ConflictChecker {
        void assertSlotAvailable(Long bingeId, LocalDate date, int startMinute,
                                 int durationMinutes, Long venueRoomId, String excludeHoldToken);
    }
}
