package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.SagaState;
import com.skbingegalaxy.booking.entity.SagaState.SagaStatus;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.SagaStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the booking saga lifecycle with a strict state machine.
 * <pre>
 * STARTED → AWAITING_PAYMENT → PAYMENT_RECEIVED → CONFIRMED → COMPLETED
 *            ↘ COMPENSATING ────→ COMPENSATED / FAILED
 * </pre>
 * Only transitions defined in {@link #VALID_TRANSITIONS} are allowed.
 * Defense-in-depth: before advancing to CONFIRMED, the orchestrator verifies
 * that collected amount ≥ total amount; on underpayment it enters COMPENSATING
 * (not just a silent refusal) to trigger automatic booking cancellation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestrator {

    private static final Map<SagaStatus, Set<SagaStatus>> VALID_TRANSITIONS = Map.of(
        SagaStatus.STARTED, Set.of(SagaStatus.AWAITING_PAYMENT, SagaStatus.COMPENSATING),
        SagaStatus.AWAITING_PAYMENT, Set.of(SagaStatus.PAYMENT_RECEIVED, SagaStatus.COMPENSATING),
        SagaStatus.PAYMENT_RECEIVED, Set.of(SagaStatus.CONFIRMED, SagaStatus.COMPENSATING),
        SagaStatus.CONFIRMED, Set.of(SagaStatus.COMPLETED, SagaStatus.COMPENSATING),
        SagaStatus.COMPENSATING, Set.of(SagaStatus.COMPENSATED, SagaStatus.FAILED),
        SagaStatus.COMPENSATED, Set.of(),
        SagaStatus.FAILED, Set.of(),
        SagaStatus.COMPLETED, Set.of()
    );

    private final SagaStateRepository sagaStateRepository;
    private final BookingRepository bookingRepository;

    @Transactional
    public SagaState startSaga(String bookingRef) {
        SagaState saga = SagaState.builder()
            .bookingRef(bookingRef)
            .sagaStatus(SagaStatus.STARTED)
            .lastCompletedStep("BOOKING_CREATED")
            .build();
        return sagaStateRepository.save(saga);
    }

    @Transactional
    public void advanceTo(String bookingRef, SagaStatus status, String completedStep) {
        sagaStateRepository.findByBookingRef(bookingRef).ifPresent(saga -> {
            Set<SagaStatus> allowed = VALID_TRANSITIONS.getOrDefault(saga.getSagaStatus(), Set.of());
            if (!allowed.contains(status)) {
                log.warn("Invalid saga transition for {}: {} → {} (allowed: {})",
                    bookingRef, saga.getSagaStatus(), status, allowed);
                return;
            }

            // Defense-in-depth: verify payment is fully collected before confirming
            if (status == SagaStatus.CONFIRMED) {
                java.util.Optional<Booking> bookingOpt = bookingRepository.findByBookingRef(bookingRef);
                if (bookingOpt.isPresent()) {
                    Booking booking = bookingOpt.get();
                    java.math.BigDecimal collected = booking.getCollectedAmount() != null
                        ? booking.getCollectedAmount() : java.math.BigDecimal.ZERO;
                    java.math.BigDecimal total = booking.getTotalAmount() != null
                        ? booking.getTotalAmount() : java.math.BigDecimal.ZERO;
                    if (collected.compareTo(total) < 0) {
                        log.error("Saga {} BLOCKED from CONFIRMED — collected ₹{} < total ₹{}; entering compensation",
                            bookingRef, collected, total);
                        saga.setSagaStatus(SagaStatus.COMPENSATING);
                        saga.setFailureReason(String.format(
                            "Underpayment: collected ₹%s < total ₹%s", collected, total));
                        saga.setCompensationAttempts(saga.getCompensationAttempts() + 1);
                        sagaStateRepository.save(saga);
                        return;
                    }
                }
            }

            saga.setSagaStatus(status);
            saga.setLastCompletedStep(completedStep);
            if (status == SagaStatus.COMPLETED || status == SagaStatus.COMPENSATED) {
                saga.setCompletedAt(LocalDateTime.now());
            }
            sagaStateRepository.save(saga);
            log.info("Saga {} advanced to {} (step: {})", bookingRef, status, completedStep);
        });
    }

    @Transactional
    public void markCompensating(String bookingRef, String reason) {
        sagaStateRepository.findByBookingRef(bookingRef).ifPresent(saga -> {
            saga.setSagaStatus(SagaStatus.COMPENSATING);
            saga.setFailureReason(reason);
            saga.setCompensationAttempts(saga.getCompensationAttempts() + 1);
            sagaStateRepository.save(saga);
            log.warn("Saga {} entering compensation: {}", bookingRef, reason);
        });
    }

    @Transactional
    public void markFailed(String bookingRef, String reason) {
        sagaStateRepository.findByBookingRef(bookingRef).ifPresent(saga -> {
            saga.setSagaStatus(SagaStatus.FAILED);
            saga.setFailureReason(reason);
            saga.setCompletedAt(LocalDateTime.now());
            sagaStateRepository.save(saga);
            log.error("Saga {} FAILED: {}", bookingRef, reason);
        });
    }

    public List<SagaState> getFailedSagas() {
        return sagaStateRepository.findBySagaStatus(SagaStatus.FAILED);
    }

    public List<SagaState> getCompensatingSagas() {
        return sagaStateRepository.findBySagaStatus(SagaStatus.COMPENSATING);
    }
}
