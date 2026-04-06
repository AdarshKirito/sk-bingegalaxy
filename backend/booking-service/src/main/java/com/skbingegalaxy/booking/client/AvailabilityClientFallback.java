package com.skbingegalaxy.booking.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback for availability-service circuit breaker.
 * Caches the last successful response per slot key so that a brief outage
 * doesn't block every booking attempt.  Falls back to null (= deny) when
 * no cached value exists.
 */
@Component
@Slf4j
public class AvailabilityClientFallback implements AvailabilityClient {

    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    /**
     * Called by application code to record a successful availability lookup.
     */
    public void cacheResult(LocalDate date, int startMinute, int durationMinutes, Boolean result) {
        if (result != null) {
            cache.put(cacheKey(date, startMinute, durationMinutes), result);
        }
    }

    @Override
    public Boolean checkSlotAvailable(LocalDate date, int startMinute, int durationMinutes) {
        String key = cacheKey(date, startMinute, durationMinutes);
        Boolean cached = cache.get(key);

        if (cached != null) {
            log.warn("Circuit breaker OPEN: returning cached availability={} for {}",
                     cached, key);
            return cached;
        }

        log.warn("Circuit breaker OPEN: no cached result for {}. Denying slot.", key);
        return null;
    }

    private String cacheKey(LocalDate date, int startMinute, int durationMinutes) {
        return date + ":" + startMinute + ":" + durationMinutes;
    }
}
