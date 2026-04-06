package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.SagaState;
import com.skbingegalaxy.booking.entity.SagaState.SagaStatus;
import com.skbingegalaxy.booking.repository.SagaStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages the booking saga lifecycle:
 * STARTED → AWAITING_PAYMENT → PAYMENT_RECEIVED → CONFIRMED → COMPLETED
 *                               ↘ COMPENSATING → COMPENSATED / FAILED
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestrator {

    private final SagaStateRepository sagaStateRepository;

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
