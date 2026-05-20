package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.SystemSettings;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.SystemSettingsRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Manages the operational date — now per-binge (preferred) with a global fallback.
 * Each binge has its own operationalDate that advances independently after its audit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemSettingsService {

    private final SystemSettingsRepository repo;
    private final BingeRepository bingeRepository;

    // ── Global fallback (no binge) ───────────────────────────

    @Transactional
    public LocalDate getOperationalDate(LocalDate clientToday) {
        LocalDate today = clientToday != null ? clientToday : LocalDate.now();
        return repo.findById(1L)
                .map(s -> {
                    LocalDate stored = s.getOperationalDate();
                    // Operational date advances only through audit.
                    // Initialize when missing and cap impossible future values,
                    // but never auto-advance just because wall-clock date changed.
                    if (stored == null) {
                        s.setOperationalDate(today);
                        repo.save(s);
                        return today;
                    }
                    if (stored.isAfter(today)) {
                        s.setOperationalDate(today);
                        repo.save(s);
                        return today;
                    }
                    return stored;
                })
                .orElseGet(() -> {
                    repo.save(SystemSettings.builder().id(1L).operationalDate(today).build());
                    return today;
                });
    }

    @Transactional
    public LocalDate advanceOperationalDate(LocalDate clientToday) {
        LocalDate cap = clientToday != null ? clientToday : LocalDate.now();
        SystemSettings settings = repo.findByIdForUpdate(1L)
                .orElseGet(() -> SystemSettings.builder().id(1L).operationalDate(cap).build());
        settings.setOperationalDate(settings.getOperationalDate().plusDays(1));
        return repo.save(settings).getOperationalDate();
    }

    // ── Per-binge operational date ───────────────────────────

    /**
     * Returns the operational date for a specific binge.
     * Initializes when missing and caps impossible future values to clientToday,
     * but does not auto-advance merely because the wall-clock date changed.
     * Falls back to global if bingeId is null.
     */
    @Transactional
    public LocalDate getOperationalDate(Long bingeId, LocalDate clientToday) {
        if (bingeId == null) return getOperationalDate(clientToday);

        LocalDate cap = clientToday != null ? clientToday : LocalDate.now();
        Binge binge = bingeRepository.findById(bingeId)
                .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));

        LocalDate opDate = binge.getOperationalDate();
        if (opDate == null) {
            binge.setOperationalDate(cap);
            bingeRepository.save(binge);
            return cap;
        }
        if (opDate.isAfter(cap)) {
            binge.setOperationalDate(cap);
            bingeRepository.save(binge);
            return cap;
        }
        return opDate;
    }

    /**
     * Advances the operational date for a specific binge by one day.
     * Falls back to global if bingeId is null.
     */
    @Transactional
    public LocalDate advanceOperationalDate(Long bingeId, LocalDate clientToday) {
        if (bingeId == null) return advanceOperationalDate(clientToday);

        Binge binge = bingeRepository.findById(bingeId)
                .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));

        LocalDate cap = clientToday != null ? clientToday : LocalDate.now();
        if (binge.getOperationalDate() == null) {
            binge.setOperationalDate(cap);
        }
        binge.setOperationalDate(binge.getOperationalDate().plusDays(1));
        bingeRepository.save(binge);
        return binge.getOperationalDate();
    }

    // ── SUPER_ADMIN override: set operational date to a specific value ──

    /** Hard guardrails for {@link #setOperationalDate}. */
    private static final int MAX_BACKWARD_DAYS = 90;
    private static final int MAX_FORWARD_DAYS = 30;

    /**
     * Force-sets the operational date for a specific binge to {@code newOpDate}.
     *
     * <p>Intended for SUPER_ADMIN use only. Caller-side authorisation is enforced
     * in the controller layer; this method enforces business safety rails:
     * <ul>
     *   <li>{@code newOpDate} must be non-null.</li>
     *   <li>{@code newOpDate} must be within {@value MAX_BACKWARD_DAYS} days
     *       behind and {@value MAX_FORWARD_DAYS} days ahead of {@code clientToday}.</li>
     * </ul>
     *
     * <p>Skipping over multiple business days is the caller's explicit
     * responsibility — bookings on intermediate dates remain in their current
     * status and will need to be resolved manually (no-show / cancel / re-date).
     * The change is logged at INFO with previous &amp; new value for ops audit.
     */
    @Transactional
    public LocalDate setOperationalDate(Long bingeId, LocalDate newOpDate, LocalDate clientToday) {
        if (bingeId == null) {
            throw new BusinessException("A venue must be selected to override its operational date.");
        }
        if (newOpDate == null) {
            throw new BusinessException("operationalDate is required.");
        }

        LocalDate today = clientToday != null ? clientToday : LocalDate.now();
        if (newOpDate.isBefore(today.minusDays(MAX_BACKWARD_DAYS))) {
            throw new BusinessException(
                "Operational date cannot be more than " + MAX_BACKWARD_DAYS + " days in the past.");
        }
        if (newOpDate.isAfter(today.plusDays(MAX_FORWARD_DAYS))) {
            throw new BusinessException(
                "Operational date cannot be more than " + MAX_FORWARD_DAYS + " days in the future.");
        }

        Binge binge = bingeRepository.findById(bingeId)
                .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));

        LocalDate previous = binge.getOperationalDate();
        if (newOpDate.equals(previous)) {
            return previous;
        }
        binge.setOperationalDate(newOpDate);
        bingeRepository.save(binge);
        log.info("[ops-audit] Binge {} ({}) operational date overridden: {} -> {} (clientToday={})",
            binge.getId(), binge.getName(), previous, newOpDate, today);
        return newOpDate;
    }
}
