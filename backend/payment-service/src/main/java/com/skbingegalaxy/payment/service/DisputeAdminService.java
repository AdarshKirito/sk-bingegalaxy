package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.payment.dto.PaymentDisputeDto;
import com.skbingegalaxy.payment.entity.PaymentDispute;
import com.skbingegalaxy.payment.repository.PaymentDisputeRepository;
import com.skbingegalaxy.common.context.BingeContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Admin-facing dispute management.
 *
 * The core dispute lifecycle is handled by {@link DisputeWebhookService} (Razorpay events).
 * This service provides the read/triage surface ops needs to:
 *   1. See all open disputes for the selected binge, sorted by respond-by deadline.
 *   2. Add internal notes while gathering evidence to submit to Razorpay.
 *
 * Respond-by deadline urgency tiers (standard Razorpay windows):
 *   RED   = < 24 h remaining
 *   AMBER = 24 – 48 h remaining
 *   GREEN = > 48 h remaining
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeAdminService {

    private static final List<String> TERMINAL_STATUSES = List.of("WON", "LOST", "ACCEPTED");

    private final PaymentDisputeRepository disputeRepository;

    /** Open disputes for the current binge, sorted by respond-by deadline ASC (most urgent first). */
    @Transactional(readOnly = true)
    public Page<PaymentDisputeDto> getOpenDisputes(Pageable pageable) {
        long bingeId = requireBingeId();
        return disputeRepository.findOpenByBingeId(bingeId, pageable).map(this::toDto);
    }

    /** All disputes (including resolved) for the current binge. */
    @Transactional(readOnly = true)
    public Page<PaymentDisputeDto> getAllDisputes(Pageable pageable) {
        long bingeId = requireBingeId();
        return disputeRepository.findAllByBingeId(bingeId, pageable).map(this::toDto);
    }

    /** Count of open disputes — used by the admin dashboard badge/alert. */
    @Transactional(readOnly = true)
    public long countOpenDisputes() {
        long bingeId = requireBingeId();
        return disputeRepository.countByBingeIdAndStatusNotIn(bingeId, TERMINAL_STATUSES);
    }

    /**
     * Append ops notes to an existing dispute record.
     * Notes are cumulative (new text is appended with a timestamp).
     * Used to record evidence gathered before submitting to Razorpay.
     */
    @Transactional
    public PaymentDisputeDto updateNotes(Long disputeId, String notes, String adminEmail) {
        long bingeId = requireBingeId();
        PaymentDispute dispute = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("PaymentDispute", "id", disputeId));

        if (!Long.valueOf(bingeId).equals(dispute.getBingeId())) {
            throw new ResourceNotFoundException("PaymentDispute", "id", disputeId);
        }
        if (TERMINAL_STATUSES.contains(dispute.getStatus())) {
            throw new BusinessException(
                "Cannot add notes to a " + dispute.getStatus() + " dispute — it is already resolved.",
                HttpStatus.CONFLICT);
        }

        String timestamp = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now(ZoneOffset.UTC));
        String newNote = "[" + timestamp + " " + adminEmail + "]: " + notes;
        String existing = dispute.getOpsNotes();
        dispute.setOpsNotes(existing == null || existing.isBlank()
            ? newNote
            : existing + "\n" + newNote);

        PaymentDispute saved = disputeRepository.save(dispute);
        log.info("Dispute {} ops notes updated by {}", disputeId, adminEmail);
        return toDto(saved);
    }

    /** Unbox with Objects.requireNonNull so the type-checker sees a @NonNull result. */
    private long requireBingeId() {
        return java.util.Objects.requireNonNull(
            BingeContext.requireBingeId(),
            "Binge context must be set before accessing dispute admin methods");
    }

    private PaymentDisputeDto toDto(PaymentDispute d) {
        Long minutesLeft = d.getRespondBy() != null
            ? ChronoUnit.MINUTES.between(LocalDateTime.now(ZoneOffset.UTC), d.getRespondBy())
            : null;
        return PaymentDisputeDto.builder()
            .id(d.getId())
            .gatewayDisputeId(d.getGatewayDisputeId())
            .paymentId(d.getPayment() != null ? d.getPayment().getId() : null)
            .transactionId(d.getPayment() != null ? d.getPayment().getTransactionId() : null)
            .bookingRef(d.getBookingRef())
            .bingeId(d.getBingeId())
            .amount(d.getAmount())
            .currency(d.getCurrency())
            .status(d.getStatus())
            .reasonCode(d.getReasonCode())
            .reasonDescription(d.getReasonDescription())
            .respondBy(d.getRespondBy())
            .gatewayCreatedAt(d.getGatewayCreatedAt())
            .opsNotes(d.getOpsNotes())
            .createdAt(d.getCreatedAt())
            .updatedAt(d.getUpdatedAt())
            .minutesUntilDeadline(minutesLeft)
            .build();
    }
}
