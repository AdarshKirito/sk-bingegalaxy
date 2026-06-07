package com.skbingegalaxy.availability.service;

import com.skbingegalaxy.availability.client.BookingBingeClient;
import com.skbingegalaxy.availability.dto.*;
import com.skbingegalaxy.availability.entity.BlockedDate;
import com.skbingegalaxy.availability.entity.BlockedSlot;
import com.skbingegalaxy.availability.repository.BlockedDateRepository;
import com.skbingegalaxy.availability.repository.BlockedSlotRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AvailabilityService {

    private final BlockedDateRepository blockedDateRepository;
    private final BlockedSlotRepository blockedSlotRepository;
    private final BookingBingeClient bookingBingeClient;

    @Value("${app.theater.opening-hour:0}")
    private int openingHour;

    @Value("${app.theater.closing-hour:24}")
    private int closingHour;

    /** Short-TTL cache of resolved [openMin, closeMin, expiryEpochMs] per binge. */
    private static final long OPERATING_WINDOW_TTL_MS = 30_000L;
    private final java.util.concurrent.ConcurrentHashMap<Long, long[]> operatingWindowCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** Cache of IANA timezone IDs per binge, same TTL as operatingWindowCache. */
    private final java.util.concurrent.ConcurrentHashMap<Long, String> venueTimezoneCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    // ── Public: get available dates in range ─────────────────
    public List<DayAvailabilityDto> getAvailability(LocalDate from, LocalDate to, LocalDate clientToday) {
        // Use the client-supplied date when present (preferred — client knows its local clock).
        // Fall back to venue-local date so a server in UTC does not treat a booking date as
        // "past" when the venue is in a UTC+ zone and the real local date is still tomorrow.
        LocalDate today = (clientToday != null) ? clientToday : venueLocalToday();
        if (from.isBefore(today)) {
            from = today;
        }
        if (to.isBefore(from)) {
            throw new BusinessException("End date must be after start date");
        }

        Set<LocalDate> blockedDates;
        Map<LocalDate, List<BlockedSlot>> blockedSlotsMap;
        Long bid = BingeContext.getBingeId();
        if (bid != null) {
            blockedDates = blockedDateRepository.findByBingeIdAndBlockedDateBetween(bid, from, to)
                .stream().map(BlockedDate::getBlockedDate).collect(Collectors.toSet());
            blockedSlotsMap = blockedSlotRepository.findByBingeIdAndSlotDateBetween(bid, from, to)
                .stream().collect(Collectors.groupingBy(BlockedSlot::getSlotDate));
        } else {
            blockedDates = blockedDateRepository.findByBlockedDateBetween(from, to)
                .stream().map(BlockedDate::getBlockedDate).collect(Collectors.toSet());
            blockedSlotsMap = blockedSlotRepository.findBySlotDateBetween(from, to)
                .stream().collect(Collectors.groupingBy(BlockedSlot::getSlotDate));
        }

        List<DayAvailabilityDto> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (blockedDates.contains(date)) {
                result.add(DayAvailabilityDto.builder()
                    .date(date)
                    .fullyBlocked(true)
                    .availableSlots(Collections.emptyList())
                    .blockedSlots(Collections.emptyList())
                    .build());
            } else {
                result.add(buildDayAvailability(date, blockedSlotsMap.getOrDefault(date, Collections.emptyList())));
            }
        }
        return result;
    }

    // ── Public: get slots for a single date ──────────────────
    public DayAvailabilityDto getSlotsForDate(LocalDate date) {
        Long bid = BingeContext.getBingeId();
        boolean dateBlocked = bid != null
            ? blockedDateRepository.existsByBingeIdAndBlockedDate(bid, date)
            : blockedDateRepository.existsByBlockedDate(date);
        if (dateBlocked) {
            return DayAvailabilityDto.builder()
                .date(date)
                .fullyBlocked(true)
                .availableSlots(Collections.emptyList())
                .blockedSlots(Collections.emptyList())
                .build();
        }
        List<BlockedSlot> blocked = bid != null
            ? blockedSlotRepository.findByBingeIdAndSlotDate(bid, date)
            : blockedSlotRepository.findBySlotDate(date);
        return buildDayAvailability(date, blocked);
    }

    // ── Admin: block full date ───────────────────────────────
    @Transactional
    public BlockedDateDto blockDate(BlockDateRequest request, Long adminId) {
        Long bid = BingeContext.getBingeId();
        boolean exists = bid != null
            ? blockedDateRepository.existsByBingeIdAndBlockedDate(bid, request.getDate())
            : blockedDateRepository.existsByBlockedDate(request.getDate());
        if (exists) {
            throw new DuplicateResourceException("BlockedDate", "date", request.getDate());
        }
        BlockedDate entity = BlockedDate.builder()
            .blockedDate(request.getDate())
            .reason(request.getReason())
            .blockedBy(adminId)
            .bingeId(bid)
            .build();
        entity = blockedDateRepository.save(entity);
        log.info("Admin {} blocked date {}", adminId, request.getDate());
        return toBlockedDateDto(entity);
    }

    // ── Admin: unblock date ──────────────────────────────────
    @Transactional
    public void unblockDate(LocalDate date) {
        Long bid = BingeContext.getBingeId();
        if (bid != null) {
            blockedDateRepository.deleteByBingeIdAndBlockedDate(bid, date);
        } else {
            blockedDateRepository.deleteByBlockedDate(date);
        }
        log.info("Unblocked date {}", date);
    }

    // ── Admin: block slot ────────────────────────────────────
    @Transactional
    public BlockedSlotDto blockSlot(BlockSlotRequest request, Long adminId) {
        if (request.getEndMinute() <= request.getStartMinute()) {
            throw new BusinessException("End time must be after start time");
        }
        Long bid = BingeContext.getBingeId();
        boolean exists = bid != null
            ? blockedSlotRepository.existsByBingeIdAndSlotDateAndStartHour(bid, request.getDate(), request.getStartMinute())
            : blockedSlotRepository.existsBySlotDateAndStartHour(request.getDate(), request.getStartMinute());
        if (exists) {
            throw new DuplicateResourceException("BlockedSlot", "slot", request.getDate() + " " + request.getStartMinute());
        }
        BlockedSlot entity = BlockedSlot.builder()
            .slotDate(request.getDate())
            .startHour(request.getStartMinute())
            .endHour(request.getEndMinute())
            .reason(request.getReason())
            .blockedBy(adminId)
            .bingeId(bid)
            .build();
        entity = blockedSlotRepository.save(entity);
        log.info("Admin {} blocked slot {} on {}", adminId, request.getStartMinute(), request.getDate());
        return toBlockedSlotDto(entity);
    }

    // ── Admin: unblock slot ──────────────────────────────────
    @Transactional
    public void unblockSlot(LocalDate date, int startMinute) {
        Long bid = BingeContext.getBingeId();
        if (bid != null) {
            blockedSlotRepository.deleteByBingeIdAndSlotDateAndStartHour(bid, date, startMinute);
        } else {
            blockedSlotRepository.deleteBySlotDateAndStartHour(date, startMinute);
        }
        log.info("Unblocked slot {} on {}", startMinute, date);
    }

    // ── Admin: list all blocked dates ────────────────────────
    public List<BlockedDateDto> getAllBlockedDates() {
        Long bid = BingeContext.getBingeId();
        if (bid == null) return List.of();
        return blockedDateRepository.findByBingeId(bid)
            .stream().map(this::toBlockedDateDto).toList();
    }

    // ── Admin: list all blocked slots ────────────────────────
    public List<BlockedSlotDto> getAllBlockedSlots() {
        Long bid = BingeContext.getBingeId();
        if (bid == null) return List.of();
        return blockedSlotRepository.findByBingeId(bid)
            .stream().map(this::toBlockedSlotDto).toList();
    }

    // ── Internal: check if a date+slot range is available (minutes-based) ──
    public boolean isSlotAvailable(LocalDate date, int startMinute, int durationMinutes) {
        Long bid = BingeContext.getBingeId();
        boolean dateBlocked = bid != null
            ? blockedDateRepository.existsByBingeIdAndBlockedDate(bid, date)
            : blockedDateRepository.existsByBlockedDate(date);
        if (dateBlocked) return false;
        // Reject anything outside the binge's operating window (consistent with grid).
        int[] window = resolveOperatingWindow();
        if (startMinute < window[0] || startMinute + durationMinutes > window[1]) {
            return false;
        }
        List<BlockedSlot> blocked = bid != null
            ? blockedSlotRepository.findByBingeIdAndSlotDate(bid, date)
            : blockedSlotRepository.findBySlotDate(date);
        // Build set of blocked 30-min indices (startHour/endHour store minutes; divide by 30 for half-hour index)
        // Use ceiling division for endHour so partial half-hours are fully blocked
        Set<Integer> blockedHalfHours = blocked.stream()
            .flatMap(s -> java.util.stream.IntStream.range(s.getStartHour() / 30, (s.getEndHour() + 29) / 30).boxed())
            .collect(Collectors.toSet());

        int endMinute = startMinute + durationMinutes;
        for (int m = startMinute; m < endMinute; m += 30) {
            int halfHourIndex = m / 30;
            if (blockedHalfHours.contains(halfHourIndex)) return false;
        }
        return true;
    }

    // ── Helpers ──────────────────────────────────────────────

    /**
     * Resolve the [openMinute, closeMinute) window for the currently-scoped binge.
     * Falls back to global app.theater.opening-hour/closing-hour when the binge
     * has no per-binge override or when booking-service is unreachable.
     * Cached per-binge with {@link #OPERATING_WINDOW_TTL_MS} TTL to avoid an
     * N-call Feign storm on every slot-grid render.
     */
    int[] resolveOperatingWindow() {
        Long bid = BingeContext.getBingeId();
        if (bid == null) {
            return new int[]{openingHour * 60, closingHour * 60};
        }
        long now = System.currentTimeMillis();
        long[] cached = operatingWindowCache.get(bid);
        if (cached != null && cached[2] > now) {
            return new int[]{(int) cached[0], (int) cached[1]};
        }
        int openMin = openingHour * 60;
        int closeMin = closingHour * 60;
        try {
            ApiResponse<BookingBingeDto> resp = bookingBingeClient.getBinge(bid);
            BookingBingeDto b = resp != null ? resp.getData() : null;
            if (b != null) {
                if (b.getOpenTime() != null) {
                    openMin = b.getOpenTime().getHour() * 60 + b.getOpenTime().getMinute();
                }
                if (b.getCloseTime() != null) {
                    closeMin = b.getCloseTime().getHour() * 60 + b.getCloseTime().getMinute();
                }
                if (b.getTimezone() != null && !b.getTimezone().isBlank()) {
                    venueTimezoneCache.put(bid, b.getTimezone());
                }
            }
        } catch (Exception ex) {
            // Rate-limit log: only warn on first miss (cached==null) within TTL window
            if (cached == null) {
                log.warn("Failed to fetch per-binge hours for binge {}; using global fallback ({}-{}): {}",
                    bid, openingHour, closingHour, ex.getMessage());
            }
        }
        if (closeMin <= openMin) {
            log.warn("Binge {} has invalid window [{},{}]; using global fallback", bid, openMin, closeMin);
            openMin = openingHour * 60;
            closeMin = closingHour * 60;
        }
        operatingWindowCache.put(bid, new long[]{openMin, closeMin, now + OPERATING_WINDOW_TTL_MS});
        return new int[]{openMin, closeMin};
    }

    /** Manually invalidate the per-binge operating-window and timezone caches. */
    public void evictOperatingWindowCache(Long bingeId) {
        if (bingeId == null) {
            operatingWindowCache.clear();
            venueTimezoneCache.clear();
        } else {
            operatingWindowCache.remove(bingeId);
            venueTimezoneCache.remove(bingeId);
        }
    }

    /**
     * Return the current date in the venue's local timezone. If the binge ID
     * is unknown or the timezone is uncached/unavailable, the cache is populated
     * via resolveOperatingWindow() (which already calls the booking-service) and
     * then the timezone is read from venueTimezoneCache. Falls back to UTC if
     * the booking-service is unreachable.
     */
    private LocalDate venueLocalToday() {
        Long bid = BingeContext.getBingeId();
        if (bid == null) return LocalDate.now(java.time.ZoneOffset.UTC);
        String tz = venueTimezoneCache.get(bid);
        if (tz == null) {
            // Warm the cache via the operating-window path (same Feign call).
            resolveOperatingWindow();
            tz = venueTimezoneCache.get(bid);
        }
        if (tz == null) return LocalDate.now(java.time.ZoneOffset.UTC);
        try {
            return LocalDate.now(java.time.ZoneId.of(tz));
        } catch (java.time.zone.ZoneRulesException e) {
            log.warn("Invalid IANA timezone '{}' for binge {} — falling back to UTC", tz, bid);
            return LocalDate.now(java.time.ZoneOffset.UTC);
        }
    }

    private DayAvailabilityDto buildDayAvailability(LocalDate date, List<BlockedSlot> blockedSlots) {
        // Build set of blocked half-hour indices (startHour/endHour store minutes; divide by 30 for half-hour index)
        // Use ceiling division for endHour so partial half-hours are fully blocked
        Set<Integer> blockedHalfHours = blockedSlots.stream()
            .flatMap(s -> java.util.stream.IntStream.range(s.getStartHour() / 30, (s.getEndHour() + 29) / 30).boxed())
            .collect(Collectors.toSet());

        List<SlotDto> available = new ArrayList<>();
        List<SlotDto> blocked = new ArrayList<>();

        // Generate 30-minute slots within this binge's operating window. The bound
        // `minute + 30 <= window[1]` ensures we never emit a slot whose end exceeds
        // the binge's closing time (e.g. close=21:45 must NOT render 21:30-22:00).
        int[] window = resolveOperatingWindow();
        for (int minute = window[0]; minute + 30 <= window[1]; minute += 30) {
            int endMinute = minute + 30;
            int halfHourIndex = minute / 30;
            boolean isAvailable = !blockedHalfHours.contains(halfHourIndex);

            SlotDto slot = SlotDto.builder()
                .startHour(minute / 60)
                .endHour(endMinute / 60)
                .startMinute(minute)
                .endMinute(endMinute)
                .label(String.format("%02d:%02d - %02d:%02d", minute / 60, minute % 60, endMinute / 60, endMinute % 60))
                .available(isAvailable)
                .build();

            if (isAvailable) {
                available.add(slot);
            } else {
                blocked.add(slot);
            }
        }

        return DayAvailabilityDto.builder()
            .date(date)
            .fullyBlocked(false)
            .availableSlots(available)
            .blockedSlots(blocked)
            .build();
    }

    private BlockedDateDto toBlockedDateDto(BlockedDate entity) {
        return BlockedDateDto.builder()
            .id(entity.getId())
            .date(entity.getBlockedDate())
            .reason(entity.getReason())
            .blockedBy(entity.getBlockedBy())
            .build();
    }

    private BlockedSlotDto toBlockedSlotDto(BlockedSlot entity) {
        return BlockedSlotDto.builder()
            .id(entity.getId())
            .date(entity.getSlotDate())
            .startMinute(entity.getStartHour())
            .endMinute(entity.getEndHour())
            .reason(entity.getReason())
            .blockedBy(entity.getBlockedBy())
            .build();
    }
}
