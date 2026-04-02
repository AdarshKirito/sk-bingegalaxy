package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.SystemSettings;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.SystemSettingsRepository;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Manages the operational date — now per-binge (preferred) with a global fallback.
 * Each binge has its own operationalDate that advances independently after its audit.
 */
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final SystemSettingsRepository repo;
    private final BingeRepository bingeRepository;

    // ── Global fallback (no binge) ───────────────────────────

    @Transactional
    public LocalDate getOperationalDate(LocalDate clientToday) {
        LocalDate cap = clientToday != null ? clientToday : LocalDate.now();
        return repo.findById(1L)
                .map(s -> {
                    if (s.getOperationalDate().isAfter(cap)) {
                        s.setOperationalDate(cap);
                        repo.save(s);
                        return cap;
                    }
                    return s.getOperationalDate();
                })
                .orElseGet(() -> {
                    repo.save(SystemSettings.builder().id(1L).operationalDate(cap).build());
                    return cap;
                });
    }

    @Transactional
    public LocalDate advanceOperationalDate(LocalDate clientToday) {
        LocalDate cap = clientToday != null ? clientToday : LocalDate.now();
        SystemSettings settings = repo.findById(1L)
                .orElseGet(() -> SystemSettings.builder().id(1L).operationalDate(cap).build());
        settings.setOperationalDate(settings.getOperationalDate().plusDays(1));
        return repo.save(settings).getOperationalDate();
    }

    // ── Per-binge operational date ───────────────────────────

    /**
     * Returns the operational date for a specific binge, capped at clientToday.
     * Falls back to global if bingeId is null.
     */
    @Transactional
    public LocalDate getOperationalDate(Long bingeId, LocalDate clientToday) {
        if (bingeId == null) return getOperationalDate(clientToday);

        LocalDate cap = clientToday != null ? clientToday : LocalDate.now();
        Binge binge = bingeRepository.findById(bingeId)
                .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));

        LocalDate opDate = binge.getOperationalDate();
        if (opDate == null || opDate.isAfter(cap)) {
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
}
