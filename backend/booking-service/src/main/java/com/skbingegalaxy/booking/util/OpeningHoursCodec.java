package com.skbingegalaxy.booking.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skbingegalaxy.booking.dto.BingeDayHours;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

/**
 * Serialize / parse a binge's per-day opening-hours JSON (see V66) and resolve the
 * hours that apply to a given day of week. Centralised so the write side
 * ({@code BingeService}) and the read/validation side ({@code BookingService}) agree
 * on the exact JSON contract and null-handling.
 *
 * <p>All methods are null- and error-tolerant: malformed stored JSON never throws
 * out of here (it degrades to "no per-day config", i.e. the caller falls back to the
 * single open/close pair), so a bad row can never brick booking validation.
 */
@Slf4j
public final class OpeningHoursCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<BingeDayHours>> LIST_TYPE = new TypeReference<>() {};

    private OpeningHoursCodec() {}

    /** Parse the stored JSON into a list; empty list on null/blank/malformed input. */
    public static List<BingeDayHours> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<BingeDayHours> parsed = MAPPER.readValue(json, LIST_TYPE);
            return parsed != null ? parsed : List.of();
        } catch (Exception e) {
            log.warn("Failed to parse opening_hours_json (ignoring, falling back to default hours): {}",
                e.getMessage());
            return List.of();
        }
    }

    /** Serialize a list to compact JSON; returns null for a null/empty list (stored as NULL). */
    public static String serialize(List<BingeDayHours> days) {
        if (days == null || days.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(days);
        } catch (Exception e) {
            log.warn("Failed to serialize opening hours; storing null: {}", e.getMessage());
            return null;
        }
    }

    /** Find the configured hours for a given day of week, if present. */
    public static Optional<BingeDayHours> forDay(List<BingeDayHours> days, DayOfWeek dow) {
        if (days == null || dow == null) return Optional.empty();
        int target = dow.getValue(); // 1=Mon..7=Sun
        return days.stream()
            .filter(d -> d.getDayOfWeek() != null && d.getDayOfWeek() == target)
            .findFirst();
    }

    /** Convenience: resolve directly from the stored JSON string. */
    public static Optional<BingeDayHours> forDay(String json, DayOfWeek dow) {
        return forDay(parse(json), dow);
    }
}
