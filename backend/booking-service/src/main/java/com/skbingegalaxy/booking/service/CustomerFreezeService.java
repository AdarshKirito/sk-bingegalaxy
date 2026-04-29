package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CreateFreezeRequest;
import com.skbingegalaxy.booking.dto.CustomerBingeFreezeDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.CustomerBingeFreeze;
import com.skbingegalaxy.booking.entity.CustomerBingeFreeze.Status;
import com.skbingegalaxy.booking.entity.CustomerBingeFreeze.TriggerType;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CustomerBingeFreezeRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages temporary booking-flow freezes per (customerId, bingeId).
 *
 * <p>Triggered automatically when a customer:
 * <ul>
 *   <li>cancels too many of their own pending bookings within the rolling
 *       freeze window — {@link Binge#getMaxPendingCancelsBeforeFreeze()};</li>
 *   <li>has too many bookings auto-cancelled by the payment-timeout
 *       scheduler — {@link Binge#getMaxPendingPaymentTimeoutsBeforeFreeze()}.</li>
 * </ul>
 *
 * <p>Admins / super-admins can lift any active freeze immediately or apply
 * one manually for support purposes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerFreezeService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_SUPER = "SUPER_ADMIN";

    private final CustomerBingeFreezeRepository freezeRepository;
    private final BingeRepository bingeRepository;
    private final BookingRepository bookingRepository;

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Throws {@link BusinessException} with HTTP 423 (LOCKED) if the customer
     * is currently frozen at the given binge. Lazy-expires stale rows.
     */
    @Transactional
    public void assertNotFrozen(Long customerId, Long bingeId) {
        CustomerBingeFreeze active = currentActiveFreeze(customerId, bingeId);
        if (active == null) return;

        long until = active.getFreezeUntil()
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli();
        String reason = active.getReason() != null ? active.getReason() : "Repeated abandoned bookings";
        // Tag the message with FROZEN: prefix so the frontend can detect & parse
        // the freeze-until epoch even if the gateway swallows the HTTP status.
        String msg = "FROZEN:until=" + until + "|reason=" + reason
            + "|message=Your booking flow at this binge is temporarily frozen until "
            + active.getFreezeUntil() + ". Please contact support to lift it sooner.";
        throw new BusinessException(msg, HttpStatus.LOCKED);
    }

    /**
     * Records a customer-initiated cancellation of a PENDING booking. If the
     * count within the rolling window meets the binge threshold, applies a
     * freeze. Best-effort: any exception here is logged and swallowed so it
     * never breaks the cancel flow.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCustomerCancellation(Long customerId, Long bingeId) {
        try {
            Binge binge = bingeRepository.findById(bingeId).orElse(null);
            if (binge == null || !binge.isFreezePolicyEnabled()) return;
            int threshold = binge.getMaxPendingCancelsBeforeFreeze();
            int windowMin = binge.getFreezeDurationMinutes();
            if (threshold <= 0 || windowMin <= 0) return;

            LocalDateTime since = LocalDateTime.now().minusMinutes(windowMin);
            // The just-cancelled booking from the outer @Transactional has not yet
            // committed when this REQUIRES_NEW transaction reads (READ_COMMITTED).
            // Add 1 to include the in-flight cancellation in the threshold check.
            long committed = bookingRepository.countCustomerPendingCancelsSince(customerId, bingeId, since);
            long count = committed + 1;
            log.debug("freeze-check pending-cancels customer={} binge={} committed={} effective={} threshold={}",
                customerId, bingeId, committed, count, threshold);
            if (count >= threshold) {
                applyAutoFreeze(customerId, bingeId, binge,
                    TriggerType.CUSTOMER_CANCELLATIONS,
                    "Cancelled " + count + " pending bookings within " + windowMin + " minutes");
            }
        } catch (Exception ex) {
            log.warn("recordCustomerCancellation failed (customer={}, binge={}): {}",
                customerId, bingeId, ex.getMessage());
        }
    }

    /**
     * Records a payment-timeout auto-cancellation. Same threshold logic but
     * keyed on {@link Binge#getMaxPendingPaymentTimeoutsBeforeFreeze()}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPendingPaymentTimeout(Long customerId, Long bingeId) {
        try {
            Binge binge = bingeRepository.findById(bingeId).orElse(null);
            if (binge == null || !binge.isFreezePolicyEnabled()) return;
            int threshold = binge.getMaxPendingPaymentTimeoutsBeforeFreeze();
            int windowMin = binge.getFreezeDurationMinutes();
            if (threshold <= 0 || windowMin <= 0) return;

            LocalDateTime since = LocalDateTime.now().minusMinutes(windowMin);
            // The just-cancelled booking from the outer @Transactional has not yet
            // committed when this REQUIRES_NEW transaction reads. Add 1 for it.
            long committed = bookingRepository.countRecentTimeoutCancellationsByBinge(customerId, bingeId, since);
            long count = committed + 1;
            log.debug("freeze-check timeouts customer={} binge={} committed={} effective={} threshold={}",
                customerId, bingeId, committed, count, threshold);
            if (count >= threshold) {
                applyAutoFreeze(customerId, bingeId, binge,
                    TriggerType.PAYMENT_TIMEOUTS,
                    count + " bookings auto-cancelled after payment timeout within " + windowMin + " minutes");
            }
        } catch (Exception ex) {
            log.warn("recordPendingPaymentTimeout failed (customer={}, binge={}): {}",
                customerId, bingeId, ex.getMessage());
        }
    }

    // ── Customer self-service ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CustomerBingeFreezeDto> listMyActiveFreezes(Long customerId) {
        LocalDateTime now = LocalDateTime.now();
        return freezeRepository.findByCustomerIdAndStatusOrderByFreezeUntilDesc(customerId, Status.ACTIVE)
            .stream()
            .filter(f -> f.getFreezeUntil().isAfter(now))
            .map(this::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public CustomerBingeFreezeDto getMyActiveFreezeForBinge(Long customerId, Long bingeId) {
        CustomerBingeFreeze active = currentActiveFreeze(customerId, bingeId);
        return active == null ? null : toDto(active);
    }

    // ── Admin operations ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CustomerBingeFreezeDto> listForBinge(Long bingeId, boolean activeOnly) {
        if (activeOnly) {
            return freezeRepository.findByBingeIdAndStatusOrderByFreezeUntilDesc(bingeId, Status.ACTIVE)
                .stream()
                .filter(f -> f.getFreezeUntil().isAfter(LocalDateTime.now()))
                .map(this::toDto)
                .toList();
        }
        return freezeRepository.findByBingeIdAndStatusOrderByFreezeUntilDesc(bingeId, Status.ACTIVE)
            .stream().map(this::toDto).toList();
    }

    @Transactional
    public CustomerBingeFreezeDto liftFreeze(Long freezeId, Long actingUserId, String role, String reason) {
        requireAdmin(role);
        CustomerBingeFreeze freeze = freezeRepository.findById(freezeId)
            .orElseThrow(() -> new ResourceNotFoundException("CustomerBingeFreeze", "id", freezeId));
        if (freeze.getStatus() != Status.ACTIVE) {
            throw new BusinessException("Freeze is not active (current: " + freeze.getStatus() + ")");
        }
        freeze.setStatus(Status.LIFTED);
        freeze.setLiftedAt(LocalDateTime.now());
        freeze.setLiftedByUserId(actingUserId);
        freeze.setLiftedReason(reason);
        log.info("Freeze {} lifted by user {} ({})", freezeId, actingUserId, role);
        return toDto(freezeRepository.save(freeze));
    }

    @Transactional
    public CustomerBingeFreezeDto createManualFreeze(CreateFreezeRequest request,
                                                     Long actingUserId, String role) {
        requireAdmin(role);
        if (request.getCustomerId() == null) throw new BusinessException("customerId is required");
        if (request.getBingeId() == null) throw new BusinessException("bingeId is required");
        int minutes = request.getDurationMinutes() != null ? request.getDurationMinutes() : 60;
        if (minutes < 1 || minutes > 7 * 24 * 60) {
            throw new BusinessException("durationMinutes must be between 1 and " + (7 * 24 * 60));
        }

        // Auto-lift any existing active freeze first
        freezeRepository.findFirstByCustomerIdAndBingeIdAndStatusOrderByFreezeUntilDesc(
                request.getCustomerId(), request.getBingeId(), Status.ACTIVE)
            .ifPresent(existing -> {
                existing.setStatus(Status.LIFTED);
                existing.setLiftedAt(LocalDateTime.now());
                existing.setLiftedByUserId(actingUserId);
                existing.setLiftedReason("Superseded by new manual freeze");
                freezeRepository.save(existing);
            });

        CustomerBingeFreeze freeze = CustomerBingeFreeze.builder()
            .customerId(request.getCustomerId())
            .bingeId(request.getBingeId())
            .freezeUntil(LocalDateTime.now().plusMinutes(minutes))
            .reason(request.getReason() != null ? request.getReason() : "Manual freeze by admin")
            .status(Status.ACTIVE)
            .triggerType(TriggerType.MANUAL)
            .triggeredByUserId(actingUserId)
            .createdAt(LocalDateTime.now())
            .build();
        log.info("Manual freeze applied by user {} to customer {} at binge {} for {} min",
            actingUserId, request.getCustomerId(), request.getBingeId(), minutes);
        return toDto(freezeRepository.save(freeze));
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private void applyAutoFreeze(Long customerId, Long bingeId, Binge binge,
                                 TriggerType trigger, String reason) {
        // Don't pile freezes on top of each other — check existing active
        if (currentActiveFreeze(customerId, bingeId) != null) {
            log.debug("Auto-freeze skipped — already active for customer={} binge={}", customerId, bingeId);
            return;
        }
        CustomerBingeFreeze freeze = CustomerBingeFreeze.builder()
            .customerId(customerId)
            .bingeId(bingeId)
            .freezeUntil(LocalDateTime.now().plusMinutes(binge.getFreezeDurationMinutes()))
            .reason(reason)
            .status(Status.ACTIVE)
            .triggerType(trigger)
            .createdAt(LocalDateTime.now())
            .build();
        freezeRepository.save(freeze);
        log.warn("Auto-freeze applied: customer={} binge={} trigger={} reason='{}' until={}",
            customerId, bingeId, trigger, reason, freeze.getFreezeUntil());
    }

    /**
     * Returns the most recent ACTIVE freeze that has not yet expired in
     * wall-clock terms; flips expired rows to EXPIRED on the way out.
     */
    private CustomerBingeFreeze currentActiveFreeze(Long customerId, Long bingeId) {
        var maybe = freezeRepository
            .findFirstByCustomerIdAndBingeIdAndStatusOrderByFreezeUntilDesc(customerId, bingeId, Status.ACTIVE);
        if (maybe.isEmpty()) return null;
        CustomerBingeFreeze freeze = maybe.get();
        if (!freeze.getFreezeUntil().isAfter(LocalDateTime.now())) {
            freeze.setStatus(Status.EXPIRED);
            freezeRepository.save(freeze);
            return null;
        }
        return freeze;
    }

    private void requireAdmin(String role) {
        if (!ROLE_ADMIN.equalsIgnoreCase(role) && !ROLE_SUPER.equalsIgnoreCase(role)) {
            throw new BusinessException("Admin privilege required", HttpStatus.FORBIDDEN);
        }
    }

    private CustomerBingeFreezeDto toDto(CustomerBingeFreeze f) {
        return CustomerBingeFreezeDto.builder()
            .id(f.getId())
            .customerId(f.getCustomerId())
            .bingeId(f.getBingeId())
            .freezeUntil(f.getFreezeUntil())
            .reason(f.getReason())
            .status(f.getStatus() != null ? f.getStatus().name() : null)
            .triggerType(f.getTriggerType() != null ? f.getTriggerType().name() : null)
            .triggeredByUserId(f.getTriggeredByUserId())
            .liftedByUserId(f.getLiftedByUserId())
            .liftedAt(f.getLiftedAt())
            .liftedReason(f.getLiftedReason())
            .createdAt(f.getCreatedAt())
            .build();
    }
}
