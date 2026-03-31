package com.skbingegalaxy.availability.service;

import com.skbingegalaxy.availability.dto.*;
import com.skbingegalaxy.availability.entity.BlockedDate;
import com.skbingegalaxy.availability.entity.BlockedSlot;
import com.skbingegalaxy.availability.repository.BlockedDateRepository;
import com.skbingegalaxy.availability.repository.BlockedSlotRepository;
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

    @Value("${app.theater.opening-hour:9}")
    private int openingHour;

    @Value("${app.theater.closing-hour:23}")
    private int closingHour;

    // ── Public: get available dates in range ─────────────────
    public List<DayAvailabilityDto> getAvailability(LocalDate from, LocalDate to, LocalDate clientToday) {
        LocalDate today = (clientToday != null) ? clientToday : LocalDate.now();
        if (from.isBefore(today)) {
            from = today;
        }
        if (to.isBefore(from)) {
            throw new BusinessException("End date must be after start date");
        }

        Set<LocalDate> blockedDates = blockedDateRepository
            .findByBlockedDateBetween(from, to)
            .stream()
            .map(BlockedDate::getBlockedDate)
            .collect(Collectors.toSet());

        Map<LocalDate, List<BlockedSlot>> blockedSlotsMap = blockedSlotRepository
            .findBySlotDateBetween(from, to)
            .stream()
            .collect(Collectors.groupingBy(BlockedSlot::getSlotDate));

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
        if (blockedDateRepository.existsByBlockedDate(date)) {
            return DayAvailabilityDto.builder()
                .date(date)
                .fullyBlocked(true)
                .availableSlots(Collections.emptyList())
                .blockedSlots(Collections.emptyList())
                .build();
        }
        List<BlockedSlot> blocked = blockedSlotRepository.findBySlotDate(date);
        return buildDayAvailability(date, blocked);
    }

    // ── Admin: block full date ───────────────────────────────
    @Transactional
    public BlockedDateDto blockDate(BlockDateRequest request, Long adminId) {
        if (blockedDateRepository.existsByBlockedDate(request.getDate())) {
            throw new DuplicateResourceException("BlockedDate", "date", request.getDate());
        }
        BlockedDate entity = BlockedDate.builder()
            .blockedDate(request.getDate())
            .reason(request.getReason())
            .blockedBy(adminId)
            .build();
        entity = blockedDateRepository.save(entity);
        log.info("Admin {} blocked date {}", adminId, request.getDate());
        return toBlockedDateDto(entity);
    }

    // ── Admin: unblock date ──────────────────────────────────
    @Transactional
    public void unblockDate(LocalDate date) {
        blockedDateRepository.deleteByBlockedDate(date);
        log.info("Unblocked date {}", date);
    }

    // ── Admin: block slot ────────────────────────────────────
    @Transactional
    public BlockedSlotDto blockSlot(BlockSlotRequest request, Long adminId) {
        if (blockedSlotRepository.existsBySlotDateAndStartHour(request.getDate(), request.getStartHour())) {
            throw new DuplicateResourceException("BlockedSlot", "slot", request.getDate() + " " + request.getStartHour());
        }
        BlockedSlot entity = BlockedSlot.builder()
            .slotDate(request.getDate())
            .startHour(request.getStartHour())
            .endHour(request.getEndHour())
            .reason(request.getReason())
            .blockedBy(adminId)
            .build();
        entity = blockedSlotRepository.save(entity);
        log.info("Admin {} blocked slot {} on {}", adminId, request.getStartHour(), request.getDate());
        return toBlockedSlotDto(entity);
    }

    // ── Admin: unblock slot ──────────────────────────────────
    @Transactional
    public void unblockSlot(LocalDate date, int startHour) {
        blockedSlotRepository.deleteBySlotDateAndStartHour(date, startHour);
        log.info("Unblocked slot {} on {}", startHour, date);
    }

    // ── Admin: list all blocked dates ────────────────────────
    public List<BlockedDateDto> getAllBlockedDates() {
        return blockedDateRepository.findAll().stream().map(this::toBlockedDateDto).toList();
    }

    // ── Admin: list all blocked slots ────────────────────────
    public List<BlockedSlotDto> getAllBlockedSlots() {
        return blockedSlotRepository.findAll().stream().map(this::toBlockedSlotDto).toList();
    }

    // ── Internal: check if a date+slot range is available (minutes-based) ──
    public boolean isSlotAvailable(LocalDate date, int startMinute, int durationMinutes) {
        if (blockedDateRepository.existsByBlockedDate(date)) return false;
        List<BlockedSlot> blocked = blockedSlotRepository.findBySlotDate(date);
        // Build set of blocked 30-min indices
        Set<Integer> blockedHalfHours = blocked.stream()
            .flatMap(s -> java.util.stream.IntStream.range(s.getStartHour() * 2, s.getEndHour() * 2).boxed())
            .collect(Collectors.toSet());

        int endMinute = startMinute + durationMinutes;
        for (int m = startMinute; m < endMinute; m += 30) {
            int halfHourIndex = m / 30;
            if (blockedHalfHours.contains(halfHourIndex)) return false;
        }
        return true;
    }

    // ── Helpers ──────────────────────────────────────────────
    private DayAvailabilityDto buildDayAvailability(LocalDate date, List<BlockedSlot> blockedSlots) {
        // Build set of blocked half-hour indices (e.g. hour 10 blocks indices 20 and 21)
        Set<Integer> blockedHalfHours = blockedSlots.stream()
            .flatMap(s -> java.util.stream.IntStream.range(s.getStartHour() * 2, s.getEndHour() * 2).boxed())
            .collect(Collectors.toSet());

        List<SlotDto> available = new ArrayList<>();
        List<SlotDto> blocked = new ArrayList<>();

        // Generate 30-minute slots from opening to closing
        for (int minute = openingHour * 60; minute < closingHour * 60; minute += 30) {
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
            .startHour(entity.getStartHour())
            .endHour(entity.getEndHour())
            .reason(entity.getReason())
            .blockedBy(entity.getBlockedBy())
            .build();
    }
}
