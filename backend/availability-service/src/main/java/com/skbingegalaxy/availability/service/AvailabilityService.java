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

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final Clock clock;

    @Value("${app.theater.opening-hour:0}")
    private int openingHour;

    @Value("${app.theater.closing-hour:24}")
    private int closingHour;

    // Mirrors booking-service's VenueClockService default so the two services
    // can never disagree about "today"/"now" near a venue's midnight boundary
    // when a venue's timezone is momentarily uncached (e.g. cold cache, or the
    // booking-service Feign call being briefly unreachable).
    @Value("${app.business.default-timezone:Asia/Kolkata}")
    private String defaultTimezoneStr;

    /** Short-TTL cache of resolved [openMin, closeMin, expiryEpochMs] per binge. */
    private static final long OPERATING_WINDOW_TTL_MS = 30_000L;
    private final java.util.concurrent.ConcurrentHashMap<Long, long[]> operatingWindowCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** Cache of IANA timezone IDs per binge, same TTL as operatingWindowCache. */
    private final java.util.concurrent.ConcurrentHashMap<Long, String> venueTimezoneCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Cache of per-day operating hours per binge, populated by the SAME Feign call that
     * warms {@link #operatingWindowCache} (no extra call). An empty list means "fetched,
     * no per-day schedule" — the single open/close window then applies to every day.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.List<BingeDayHoursDto>> openingHoursCache =
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

    // ── Admin: block full date (whole venue) ─────────────────────────────
    // Per-room blocking goes through booking-service's room_blocks (V57).
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

    // ── Admin: block slot (whole venue) ──────────────────────────────────
    // Per-room blocking goes through booking-service's room_blocks (V57).
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
        // Reject anything on a per-day CLOSED weekday, or outside the day's operating
        // window (consistent with the grid + booking-service's own hours guard).
        int[] window = resolveWindowForDate(date);
        if (window == null) return false; // venue closed this weekday
        if (startMinute < window[0] || startMinute + durationMinutes > window[1]) {
            return false;
        }
        // Reject a slot that has already started, venue-local. This is the authoritative
        // check invoked by booking-service at actual booking-creation time (via Feign),
        // so it must reject a same-day past slot even if the slot grid was never
        // re-fetched by the client (stale UI, replayed request, scripted client).
        LocalDateTime venueNow = venueLocalNow();
        if (date.isBefore(venueNow.toLocalDate())) return false;
        if (date.isEqual(venueNow.toLocalDate())
            && startMinute <= venueNow.getHour() * 60 + venueNow.getMinute()) {
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

    // ── Admin: unblock by row id (precise deletes for the calendar UI) ────
    @Transactional
    public void unblockDateById(Long id) {
        BlockedDate row = blockedDateRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Blocked date not found", org.springframework.http.HttpStatus.NOT_FOUND));
        requireSameBinge(row.getBingeId());
        blockedDateRepository.delete(row);
        log.info("Unblocked date {} (id={})", row.getBlockedDate(), id);
    }

    @Transactional
    public void unblockSlotById(Long id) {
        BlockedSlot row = blockedSlotRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Blocked slot not found", org.springframework.http.HttpStatus.NOT_FOUND));
        requireSameBinge(row.getBingeId());
        blockedSlotRepository.delete(row);
        log.info("Unblocked slot {} on {} (id={})", row.getStartHour(), row.getSlotDate(), id);
    }

    /** A block row may only be deleted from within the binge that owns it. */
    private void requireSameBinge(Long rowBingeId) {
        Long bid = BingeContext.getBingeId();
        if (rowBingeId != null && !rowBingeId.equals(bid)) {
            throw new BusinessException("This block belongs to a different venue",
                org.springframework.http.HttpStatus.FORBIDDEN);
        }
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
            // Anonymous read: no userId → no permission payload computed.
            ApiResponse<BookingBingeDto> resp = bookingBingeClient.getBinge(bid, null);
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
                openingHoursCache.put(bid, b.getOpeningHours() != null ? b.getOpeningHours() : java.util.List.of());
            }
        } catch (Exception ex) {
            // Rate-limit log: only warn on first miss (cached==null) within TTL window
            if (cached == null) {
                log.warn("Failed to fetch per-binge hours for binge {}; using global fallback ({}-{}): {}",
                    bid, openingHour, closingHour, ex.getMessage());
            }
            // Ensure a non-null cache entry so per-day resolution degrades to the single window.
            openingHoursCache.putIfAbsent(bid, java.util.List.of());
        }
        if (closeMin <= openMin) {
            log.warn("Binge {} has invalid window [{},{}]; using global fallback", bid, openMin, closeMin);
            openMin = openingHour * 60;
            closeMin = closingHour * 60;
        }
        operatingWindowCache.put(bid, new long[]{openMin, closeMin, now + OPERATING_WINDOW_TTL_MS});
        return new int[]{openMin, closeMin};
    }

    /** Manually invalidate the per-binge operating-window, timezone and per-day-hours caches. */
    public void evictOperatingWindowCache(Long bingeId) {
        if (bingeId == null) {
            operatingWindowCache.clear();
            venueTimezoneCache.clear();
            openingHoursCache.clear();
        } else {
            operatingWindowCache.remove(bingeId);
            venueTimezoneCache.remove(bingeId);
            openingHoursCache.remove(bingeId);
        }
    }

    /**
     * Resolve the {@code [openMinute, closeMinute)} window for a SPECIFIC date, honouring the
     * binge's per-day operating hours. Returns {@code null} when the venue is CLOSED that
     * weekday. Falls back to the single-window {@link #resolveOperatingWindow()} (which also
     * warms the caches) when there is no per-day schedule or none matches the date's weekday.
     */
    int[] resolveWindowForDate(LocalDate date) {
        int[] def = resolveOperatingWindow(); // warms caches + gives the default window
        Long bid = BingeContext.getBingeId();
        if (bid == null || date == null) return def;
        java.util.List<BingeDayHoursDto> oh = openingHoursCache.get(bid);
        if (oh == null || oh.isEmpty()) return def;
        int dow = date.getDayOfWeek().getValue(); // 1=Mon..7=Sun
        for (BingeDayHoursDto d : oh) {
            if (d.getDayOfWeek() == null || d.getDayOfWeek() != dow) continue;
            if (d.isClosed()) return null; // venue closed this day
            int openMin = def[0];
            int closeMin = def[1];
            if (d.getOpenTime() != null) openMin = d.getOpenTime().getHour() * 60 + d.getOpenTime().getMinute();
            if (d.getCloseTime() != null) closeMin = d.getCloseTime().getHour() * 60 + d.getCloseTime().getMinute();
            if (closeMin <= openMin) return def; // mis-configured → safe fallback
            return new int[]{openMin, closeMin};
        }
        return def;
    }

    /**
     * Resolve the venue's IANA timezone. If the binge ID is unknown or the
     * timezone is uncached/unavailable, the cache is populated via
     * resolveOperatingWindow() (which already calls the booking-service) and
     * then the timezone is read from venueTimezoneCache. Falls back to
     * {@code app.business.default-timezone} (same key/default as booking-service's
     * VenueClockService) rather than UTC, so the two services never disagree
     * about "today" purely because one of them missed its cache.
     */
    private ZoneId venueZone() {
        Long bid = BingeContext.getBingeId();
        if (bid == null) return defaultZone();
        String tz = venueTimezoneCache.get(bid);
        if (tz == null) {
            // Warm the cache via the operating-window path (same Feign call).
            resolveOperatingWindow();
            tz = venueTimezoneCache.get(bid);
        }
        if (tz == null) return defaultZone();
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            log.warn("Invalid IANA timezone '{}' for binge {} — falling back to default", tz, bid);
            return defaultZone();
        }
    }

    private ZoneId defaultZone() {
        if (defaultTimezoneStr == null || defaultTimezoneStr.isBlank()) {
            return ZoneId.of("Asia/Kolkata");
        }
        try {
            return ZoneId.of(defaultTimezoneStr);
        } catch (DateTimeException e) {
            log.error("Invalid app.business.default-timezone '{}', falling back to Asia/Kolkata", defaultTimezoneStr);
            return ZoneId.of("Asia/Kolkata");
        }
    }

    /**
     * BOOK-003 — DST spring-forward guard. On the day a DST-observing venue
     * springs forward, the wall clock jumps over an hour (e.g. America/Chicago
     * 02:00→03:00 on the March transition), so a minute-of-day landing in that
     * gap names a LOCAL time that never actually occurs. Offering such a slot
     * would let a customer "book" an instant that does not exist — the booking's
     * real instant would silently shift by the DST offset. This returns
     * {@code false} for those non-existent wall-clock slots so slot generation
     * skips them.
     *
     * <p>Fall-back overlaps (a wall-clock hour that occurs twice, e.g. the
     * November transition) are intentionally left VALID — the time genuinely
     * exists (twice); {@link java.time.ZonedDateTime} resolves it to the earlier
     * offset, which is the correct first-occurrence booking.
     *
     * <p>For zones without DST (e.g. {@code Asia/Kolkata}, the default) the zone
     * rules report no transition for any future local time, so this is a no-op
     * and slot behaviour is byte-for-byte unchanged.
     */
    private boolean slotWallClockExists(LocalDate date, int startMinute, ZoneId zone) {
        LocalDateTime slotStart = date.atStartOfDay().plusMinutes(startMinute);
        java.time.zone.ZoneOffsetTransition transition = zone.getRules().getTransition(slotStart);
        return transition == null || !transition.isGap();
    }

    /** Current date in the venue's local timezone (see {@link #venueZone()}). */
    private LocalDate venueLocalToday() {
        return LocalDate.now(clock.withZone(venueZone()));
    }

    /** Current wall-clock instant in the venue's local timezone (see {@link #venueZone()}). */
    private LocalDateTime venueLocalNow() {
        return LocalDateTime.now(clock.withZone(venueZone()));
    }

    private DayAvailabilityDto buildDayAvailability(LocalDate date, List<BlockedSlot> blockedSlots) {
        // Per-day operating hours: a weekday the venue marks CLOSED yields no slots at all,
        // and is flagged so the customer date picker can render it as "Closed".
        int[] window = resolveWindowForDate(date);
        if (window == null) {
            return DayAvailabilityDto.builder()
                .date(date)
                .fullyBlocked(true)
                .closed(true)
                .availableSlots(Collections.emptyList())
                .blockedSlots(Collections.emptyList())
                .build();
        }
        // Build set of blocked half-hour indices (startHour/endHour store minutes; divide by 30 for half-hour index)
        // Use ceiling division for endHour so partial half-hours are fully blocked
        Set<Integer> blockedHalfHours = blockedSlots.stream()
            .flatMap(s -> java.util.stream.IntStream.range(s.getStartHour() / 30, (s.getEndHour() + 29) / 30).boxed())
            .collect(Collectors.toSet());

        // A slot that has already started (venue-local) can never be booked, even if
        // nothing admin-blocked it. Without this, "today" always renders its full
        // operating-window grid regardless of the current time, so a customer could
        // pick e.g. a 10:00 slot at 16:00 venue-local — the booking then fails the
        // no-show sweep almost immediately since its reservation window is already over.
        LocalDateTime venueNow = venueLocalNow();
        LocalDate venueToday = venueNow.toLocalDate();
        int nowMinuteOfDay = venueNow.getHour() * 60 + venueNow.getMinute();
        // Resolved once for the DST spring-forward guard below (no-op for non-DST zones).
        ZoneId venueZone = venueZone();

        List<SlotDto> available = new ArrayList<>();
        List<SlotDto> blocked = new ArrayList<>();

        // Generate 30-minute slots within this binge's operating window for THIS date
        // (per-day hours resolved above). The bound `minute + 30 <= window[1]` ensures we
        // never emit a slot whose end exceeds the day's closing time (e.g. close=21:45 must
        // NOT render 21:30-22:00).
        for (int minute = window[0]; minute + 30 <= window[1]; minute += 30) {
            // BOOK-003: on a DST spring-forward day this wall-clock time may not exist
            // in the venue's zone — never offer a phantom slot (no-op for non-DST venues).
            if (!slotWallClockExists(date, minute, venueZone)) {
                continue;
            }
            int endMinute = minute + 30;
            int halfHourIndex = minute / 30;
            boolean isPast = date.isBefore(venueToday)
                || (date.isEqual(venueToday) && minute <= nowMinuteOfDay);
            boolean isAvailable = !isPast && !blockedHalfHours.contains(halfHourIndex);

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
