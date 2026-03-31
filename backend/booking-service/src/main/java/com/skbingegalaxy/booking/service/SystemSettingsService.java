package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.SystemSettings;
import com.skbingegalaxy.booking.repository.SystemSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Manages the single-row SystemSettings record.
 * The operationalDate is the "current business day" as seen by the system.
 * It advances by 1 day only after a successful nightly audit.
 *
 * All "today" comparisons use the *client*'s local date (sent by the browser)
 * so that UTC-offset containers don't drift ahead of the admin's timezone.
 */
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final SystemSettingsRepository repo;

    /**
     * Returns the current operational date, capped at {@code clientToday}.
     * If the DB has a date that is AHEAD of the client's today it is
     * automatically corrected and persisted.
     *
     * @param clientToday the date the admin's browser considers "today"
     *                    (may be null to fall back to the server's date)
     */
    @Transactional
    public LocalDate getOperationalDate(LocalDate clientToday) {
        LocalDate cap = clientToday != null ? clientToday : LocalDate.now();
        return repo.findById(1L)
                .map(s -> {
                    if (s.getOperationalDate().isAfter(cap)) {
                        // Stored date is in the future relative to client's today – fix it
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

    /**
     * Advances the operational date by exactly one day.
     * Called at the end of a successful audit.
     */
    @Transactional
    public LocalDate advanceOperationalDate(LocalDate clientToday) {
        LocalDate cap = clientToday != null ? clientToday : LocalDate.now();
        SystemSettings settings = repo.findById(1L)
                .orElseGet(() -> SystemSettings.builder().id(1L).operationalDate(cap).build());
        settings.setOperationalDate(settings.getOperationalDate().plusDays(1));
        return repo.save(settings).getOperationalDate();
    }
}
