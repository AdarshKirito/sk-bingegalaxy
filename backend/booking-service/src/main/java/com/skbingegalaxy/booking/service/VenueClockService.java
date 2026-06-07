package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.repository.BingeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for resolving the timezone of a venue (Binge).
 *
 * <p>Design contract (mirrors how Airbnb / Booking.com handle this):
 * <ul>
 *   <li>Every date/time comparison that is "business-meaningful" (booking-date
 *       validation, check-in window, tax-rule effective dates) uses the
 *       <em>venue's</em> timezone — never the JVM default and never a hardcoded
 *       constant.</li>
 *   <li>Point-in-time audit events (paidAt, actualCheckInTime, createdAt) are
 *       recorded as UTC via {@code Instant.now()} or
 *       {@code LocalDateTime.now(ZoneOffset.UTC)} — they are not venue-local.</li>
 *   <li>When a venue has no timezone configured the platform default kicks in,
 *       controlled by {@code app.business.default-timezone} (env-overridable).</li>
 * </ul>
 *
 * <p>ZoneId instances are cached per-venue after the first resolution. Call
 * {@link #evict(Long)} when a venue's timezone is updated through the admin API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VenueClockService {

    @Value("${app.business.default-timezone:Asia/Kolkata}")
    private String defaultTimezoneStr;

    private final BingeRepository bingeRepository;

    private final ConcurrentHashMap<Long, ZoneId> cache = new ConcurrentHashMap<>();

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the ZoneId for the given venue. Falls back to the platform default
     * when {@code bingeId} is null or the venue has no timezone configured.
     */
    public ZoneId zoneOf(Long bingeId) {
        if (bingeId == null) return defaultZone();
        return cache.computeIfAbsent(bingeId, this::resolve);
    }

    /** Current date in the venue's local timezone. */
    public LocalDate today(Long bingeId) {
        return LocalDate.now(zoneOf(bingeId));
    }

    /** Current wall-clock time in the venue's local timezone. */
    public LocalDateTime now(Long bingeId) {
        return LocalDateTime.now(zoneOf(bingeId));
    }

    /**
     * The platform-level default zone (driven by {@code app.business.default-timezone}).
     * Use this when no venue id is available (e.g. anonymous tax preview).
     */
    public ZoneId defaultZone() {
        try {
            return ZoneId.of(defaultTimezoneStr);
        } catch (DateTimeException e) {
            log.error("Invalid app.business.default-timezone '{}', falling back to Asia/Kolkata",
                defaultTimezoneStr);
            return ZoneId.of("Asia/Kolkata");
        }
    }

    /**
     * Removes a cached ZoneId so the next call re-reads from the database.
     * Call this whenever a venue's {@code timezone} field is updated.
     */
    public void evict(Long bingeId) {
        if (bingeId != null) cache.remove(bingeId);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private ZoneId resolve(Long bingeId) {
        return bingeRepository.findById(bingeId)
            .map(b -> b.getTimezone())
            .filter(tz -> tz != null && !tz.isBlank())
            .map(tz -> {
                try {
                    return ZoneId.of(tz);
                } catch (DateTimeException e) {
                    log.warn("Binge {} has unrecognised timezone '{}', using platform default",
                        bingeId, tz);
                    return defaultZone();
                }
            })
            .orElseGet(() -> {
                log.debug("Binge {} has no timezone set, using platform default", bingeId);
                return defaultZone();
            });
    }
}
